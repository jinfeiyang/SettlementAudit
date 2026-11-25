package com.zjlt.preaudit.module.check.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.ai.client.AiServiceClient;
import com.zjlt.preaudit.module.system.service.AiSettingsService;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqItemMapper;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.project.service.ProjectService;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import com.zjlt.preaudit.module.rule.service.RuleService;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 核查执行器：清单逐行核算 → 定额三级匹配 → 取费核查 → 疑点分级入库。
 *
 * <p>分级口径（不再用「确定/需复核/提示」当级别别名）：
 * 高＝证据完整且偏差达到高风险阈值（数量/金额一致性错误、有价行未套定额、取费偏差大且可核算行占比≥80%）；
 * 中＝证据完整但偏差较小，或匹配靠语义召回/单位换算、图纸无对应、取费基数含估算值；
 * 低＝信息不足无法判定，仅作提示。
 * LLM 仅生成归因解释（ai_note），不参与金额与结论。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckRunner {

    private final AsyncTaskMapper taskMapper;
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final BoqItemMapper boqItemMapper;
    private final IssueMapper issueMapper;
    private final RuleService ruleService;
    private final QuotaMatchService quotaMatchService;
    private final FeeCheckService feeCheckService;
    private final DrawingCheckService drawingCheckService;
    private final AiServiceClient aiClient;
    private final AiSettingsService aiSettingsService;

    @Value("${preaudit.check.amount-tolerance:1.00}")
    private BigDecimal amountTolerance;

    @Value("${preaudit.check.fee-deviation-threshold:0.05}")
    private BigDecimal feeThreshold;

    @Value("${preaudit.check.fee-deviation-high-threshold:0.15}")
    private BigDecimal feeHighThreshold;

    /** 可核算行占比低于该值时取费基数靠估算，证据不足，偏差再大也只给中风险 */
    private static final BigDecimal FEE_FULL_COVERAGE = new BigDecimal("80");

    @Async("checkExecutor")
    public void runAsync(Long taskId) {
        try {
            execute(taskId);
        } catch (Exception e) {
            log.error("核查任务失败 taskId={}", taskId, e);
            fail(taskId, "核查失败：" + e.getMessage());
        }
    }

    public void execute(Long taskId) {
        long start = System.currentTimeMillis();
        AsyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Project project = projectService.requireProject(task.getProjectId());
        List<BoqItem> allItems = boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                .eq(BoqItem::getProjectId, project.getId())
                .orderByAsc(BoqItem::getRowNo));
        // 混合计价项目：存在固定单价施工费 / 其他费 / 规则取费行时，取费核查只覆盖定额法那一小块，
        // 不能拿它跟全口径送审金额比偏差（会放大成几千个百分点），改为口径提示。
        boolean mixedPricing = allItems.stream().anyMatch(i ->
                Dict.isPricedExtra(i.getItemType())
                        || (i.getFeatureDesc() != null && i.getFeatureDesc().contains(Dict.FIXED_PRICE_TAG)));
        // 取费/固定单价/其他费行都是已计价或规则生成的行，不是原始工程量行：
        // 排除以免重复计费、误报缺套定额；它们只参与清单合计展示。
        List<BoqItem> items = allItems.stream().filter(i -> !Dict.isPricedExtra(i.getItemType())).toList();
        if (items.isEmpty()) {
            fail(taskId, "清单为空，请先导入工程量清单后再核查");
            return;
        }
        task.setStatus(Dict.TASK_RUNNING);
        task.setTotal(items.size());
        task.setProgress(2);
        task.setMessage("开始核查");
        taskMapper.updateById(task);

        List<RuleQuota> quotas = ruleService.listEnabledQuotas();
        List<RuleFee> fees = ruleService.listEnabledFees(project.getEngType());

        // 重跑核查：清理上一轮疑点，保证结论可追溯（避免重复项）
        issueMapper.delete(Wrappers.<Issue>lambdaQuery().eq(Issue::getProjectId, project.getId()));

        List<Issue> issues = new ArrayList<>();
        int hitTop3 = 0;
        int matchedExact = 0;
        int quotaRows = 0;
        for (int i = 0; i < items.size(); i++) {
            BoqItem item = items.get(i);
            // 甲供材料/甲供设备是物资行，本身不套定额：不参与定额匹配，也不报「未匹配定额」
            boolean quotaRow = item.getItemType() == null || Dict.BOQ_QUOTA.equals(item.getItemType());
            CheckDtos.MatchResult match = null;
            if (quotaRow) {
                quotaRows++;
                match = quotaMatchService.match(item, quotas);
                // 指标口径：前三级命中 = 精确匹配(1) 或 语义召回达到阈值(2) 的行占比
                if (match.level() <= 2) {
                    hitTop3++;
                    if (match.level() == 1) {
                        matchedExact++;
                    }
                }
                applyMatch(item, match);
                boqItemMapper.updateById(item);
            }
            collectItemIssues(project.getId(), item, match, issues);
            if (i % 10 == 0 || i == items.size() - 1) {
                task.setProgress(Math.min(85, 5 + (int) ((i + 1) * 80.0 / items.size())));
                task.setMessage("已核查 " + (i + 1) + "/" + items.size() + " 行");
                taskMapper.updateById(task);
            }
        }

        CheckDtos.FeeResult feeResult = feeCheckService.check(project, items, quotas, fees);
        collectFeeIssues(project.getId(), feeResult, fees, issues, mixedPricing);

        // B3 图纸-清单核对：用竣工图解析出的材料表跟清单比量（没有图纸解析结果时自动跳过）
        task.setProgress(88);
        task.setMessage("正在核对图纸材料与清单");
        taskMapper.updateById(task);
        issues.addAll(drawingCheckService.check(project, items));

        Map<Long, BoqItem> itemById = allItems.stream()
                .filter(x -> x.getId() != null)
                .collect(java.util.stream.Collectors.toMap(BoqItem::getId, x -> x, (a, b) -> a));
        enrichWithAi(issues, itemById, quotas, fees);
        issues.forEach(issueMapper::insert);

        int high = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_HIGH).count();
        int medium = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_MEDIUM).count();
        int low = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_LOW).count();
        BigDecimal hitRate = quotaRows == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(hitTop3 * 100.0 / quotaRows).setScale(2, RoundingMode.HALF_UP);

        CheckDtos.CheckSummary summary = new CheckDtos.CheckSummary(items.size(), issues.size(), high, medium, low,
                hitRate, feeResult.directCost(), feeResult.feeTotal(), feeResult.expectedAmount(),
                feeResult.settleAmount(), mixedPricing ? null : feeResult.deviation(),
                System.currentTimeMillis() - start);

        task.setStatus(Dict.TASK_SUCCESS);
        task.setProgress(100);
        task.setMessage(String.format("核查完成：%d 行清单，疑点 %d 条（高 %d / 中 %d / 低 %d），定额前三命中率 %s%%，精确匹配 %d 行",
                items.size(), issues.size(), high, medium, low, hitRate.toPlainString(), matchedExact));
        task.setResultJson(JsonUtil.toJson(summary));
        taskMapper.updateById(task);

        updateProjectStatus(project.getId(), Dict.PROJ_CHECKED);
        log.info("核查完成 projectId={} items={} issues={} 耗时={}ms", project.getId(), items.size(),
                issues.size(), summary.durationMs());
    }

    private void applyMatch(BoqItem item, CheckDtos.MatchResult match) {
        CheckDtos.QuotaCandidate best = match.best();
        item.setMatchLevel(match.level());
        item.setMatchScore(match.bestScore());
        if (best == null) {
            item.setMatchQuotaCode(null);
            item.setMatchQuotaName(null);
            return;
        }
        if (match.level() == 1) {
            item.setMatchQuotaCode(best.quotaCode());
            item.setMatchQuotaName(best.quotaName());
            item.setStdItemCode(best.quotaCode());
            item.setStdItemName(best.quotaName());
        } else if (match.level() == 2) {
            item.setMatchQuotaCode(best.quotaCode());
            item.setMatchQuotaName(best.quotaName());
        }
    }

    private void collectItemIssues(Long projectId, BoqItem item, CheckDtos.MatchResult match, List<Issue> issues) {
        String rowRef = item.getRowNo() == null ? null : String.valueOf(item.getRowNo());
        String where = "清单第 " + (item.getRowNo() == null ? "?" : item.getRowNo()) + " 行「"
                + TextUtil.nullToEmpty(item.getItemName()) + "」";

        boolean materialRow = item.getItemType() != null && !Dict.BOQ_QUOTA.equals(item.getItemType());

        // 工程量与金额一致性（确定性，只核清单自身数据，不涉及图纸）
        if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QTY, Dict.LEVEL_HIGH, "B0-QTY",
                    "工程量为空或非正数", where + " 工程量为 " + item.getQuantity() + "，应补充实际工程量。"));
        }
        // 单价/合价只有清单本身带价才有意义：表三甲这类无价清单没有单价列，
        // 甲供物资的金额是领用金额（按领出量计），都不能按「数量×单价」去套。
        if (!materialRow && item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QTY, Dict.LEVEL_MEDIUM, "B0-PRICE",
                    "综合单价为 0", where + " 综合单价列填的是 0，请核对清单与结算书。"));
        }
        if (!materialRow && item.getUnitPrice() == null && item.getTotalPrice() != null) {
            issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QTY, Dict.LEVEL_MEDIUM, "B0-PRICE",
                    "有合价但缺少综合单价",
                    where + " 合价 " + item.getTotalPrice().toPlainString() + " 元，但综合单价为空，无法核算单价合理性。"));
        }
        if (!materialRow && item.getQuantity() != null && item.getUnitPrice() != null && item.getTotalPrice() != null) {
            BigDecimal expected = item.getQuantity().multiply(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diff = item.getTotalPrice().subtract(expected).abs();
            if (diff.compareTo(amountTolerance) > 0) {
                issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QTY, Dict.LEVEL_HIGH, "B0-AMOUNT",
                        "合价与「数量×单价」不一致",
                        where + " 合价 " + item.getTotalPrice().toPlainString() + " 元，数量×单价应为 "
                                + expected.toPlainString() + " 元，差额 " + diff.toPlainString() + " 元。"));
            }
        }

        // 定额套用（B1）：只有定额子目行才判套用；甲供物资行不套定额，不产出错套/缺套疑点
        if (!materialRow && match != null) {
            CheckDtos.QuotaCandidate best = match.best();
            String candidates = match.candidates().stream()
                    .map(c -> c.quotaCode() + " " + c.quotaName() + "（" + c.score().toPlainString() + "）")
                    .reduce((a, b) -> a + "；" + b).orElse("无");
            if (match.level() == 3) {
                // 有结算价/单价的行未套定额会直接影响金额，按高风险处理；
                // 表三甲这类无价行更可能是规则库缺项或计价口径不同，先按需复核处理，不一刀切判高。
                boolean charged = (item.getUnitPrice() != null && item.getUnitPrice().signum() > 0)
                        || (item.getTotalPrice() != null && item.getTotalPrice().signum() > 0);
                issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QUOTA,
                        charged ? Dict.LEVEL_HIGH : Dict.LEVEL_MEDIUM, "B1-MISS",
                        "未匹配到定额子目（错套/缺套风险）",
                        where + " 清单未填定额编号，" + match.message() + "。候选：" + candidates
                                + "。" + (charged ? "该行有单价/合价，未套定额会直接影响送审金额。"
                                : "该行无单价/合价，可能是规则库缺项或计价口径不同，先按需复核处理。")
                                + "建议补填定额编号，或在「规则库-定额子目」补充后再核查。"));
            } else if (match.level() == 2) {
                issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QUOTA, Dict.LEVEL_MEDIUM, "B1-SEMANTIC",
                        "定额套用需复核（语义召回命中）",
                        where + " 最佳推荐 " + best.quotaCode() + " " + best.quotaName()
                                + "（置信度 " + match.bestScore().toPlainString() + "）。前三候选：" + candidates
                                + "。请确认定额套用与项目特征是否一致。"));
            }
            // 三级：换算系数校验
            if (best != null && !match.unitConvertible()) {
                issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QUOTA, Dict.LEVEL_MEDIUM, "B1-CONVERT",
                        "计量单位与推荐定额不一致，需确认换算系数",
                        where + " 清单单位「" + item.getUnit() + "」，推荐定额 " + best.quotaCode()
                                + " 单位为「" + best.unit() + "」，规则库未登记换算关系，无法自动核算换算结果。"));
            }
        }
    }

    private void collectFeeIssues(Long projectId, CheckDtos.FeeResult fee, List<RuleFee> fees,
                                  List<Issue> issues, boolean mixedPricing) {
        if (fees.isEmpty()) {
            Issue issue = new Issue();
            issue.setProjectId(projectId);
            issue.setIssueType(Dict.ISSUE_FEE);
            issue.setLevel(Dict.LEVEL_LOW);
            issue.setRuleCode("B2-NO-RULE");
            issue.setRefType("PROJECT");
            issue.setTitle("未配置适用取费规则，取费核查未执行");
            issue.setDetail("规则库中未找到本工程类型可用的取费规则，请在「规则库-取费规则」中补充后重新核查。");
            issue.setReviewStatus(Dict.REVIEW_PENDING);
            issues.add(issue);
            return;
        }
        // 混合计价：清单里含固定单价施工费、其他费、规则取费行等已计价部分，
        // 而取费核查只基于定额工程量行。两者口径不同，拿局部应计金额与全口径送审金额比
        // 会算出几千个百分点的假偏差，因此只给口径提示，不下偏差结论。
        if (mixedPricing) {
            Issue issue = new Issue();
            issue.setProjectId(projectId);
            issue.setIssueType(Dict.ISSUE_FEE);
            issue.setLevel(Dict.LEVEL_LOW);
            issue.setRuleCode("B2-MIXED-PRICING");
            issue.setRefType("PROJECT");
            issue.setTitle("混合计价项目，取费偏差未判定");
            issue.setDetail("本项目清单含固定单价施工费 / 工程建设其他费 / 规则取费行，"
                    + "属混合计价。取费核查按现行规则仅覆盖定额法部分：直接费 "
                    + fee.directCost().toPlainString() + " 元，人工费基数 "
                    + fee.laborCost().toPlainString() + " 元，核算取费合计 "
                    + fee.feeTotal().toPlainString() + " 元，该部分应计合计 "
                    + fee.expectedAmount().toPlainString() + " 元。"
                    + "这部分金额与完整送审金额 " + (fee.settleAmount() == null ? "未填写"
                    : fee.settleAmount().toPlainString()) + " 元口径不同，不做全口径偏差比较。"
                    + "清单合计已在「工程量清单」按结算口径汇总，可直接与送审金额核对。");
            issue.setReviewStatus(Dict.REVIEW_PENDING);
            issues.add(issue);
            return;
        }
        if (fee.deviation() == null) {
            Issue issue = new Issue();
            issue.setProjectId(projectId);
            issue.setIssueType(Dict.ISSUE_FEE);
            issue.setLevel(Dict.LEVEL_LOW);
            issue.setRefType("PROJECT");
            if (fee.settleAmount() == null) {
                issue.setRuleCode("B2-NO-SETTLE");
                issue.setTitle("项目未填写结算送审金额，取费偏差校验未执行");
                issue.setDetail("已按现行取费规则核算应计金额 " + fee.expectedAmount().toPlainString()
                        + " 元（直接费 " + fee.directCost().toPlainString() + " 元，取费 "
                        + fee.feeTotal().toPlainString()
                        + " 元）。请在项目信息中填写结算送审金额后重新核查。");
            } else {
                // 清单大量行既没价、也套不到有基价的定额：基数算不准，不能拿偏差下结论
                issue.setRuleCode("B2-NO-PRICE");
                issue.setTitle("清单价格信息不足，取费偏差未判定");
                issue.setDetail("清单可核算行占比仅 " + fee.coverage().toPlainString() + "%（低于 50%），"
                        + "按定额基价估算的直接费 " + fee.estimatedAmount().toPlainString() + " 元，"
                        + "核算直接费合计 " + fee.directCost().toPlainString() + " 元。"
                        + "表三甲这类只有定额编号和工程量的清单需要单价/合价才能核算取费，"
                        + "请补全清单价格信息或在「规则库-定额子目」补齐定额基价后重新核查。");
            }
            issue.setReviewStatus(Dict.REVIEW_PENDING);
            issues.add(issue);
            return;
        }
        BigDecimal abs = fee.deviation().abs();
        if (abs.compareTo(feeThreshold) <= 0) {
            return;
        }
        int level = abs.compareTo(feeHighThreshold) > 0 ? Dict.LEVEL_HIGH : Dict.LEVEL_MEDIUM;
        // 证据充分度：基数里有大量估算值时不能下确定结论，降级为中风险并在明细里说明
        boolean weakEvidence = fee.coverage().compareTo(FEE_FULL_COVERAGE) < 0;
        if (weakEvidence && level == Dict.LEVEL_HIGH) {
            level = Dict.LEVEL_MEDIUM;
        }
        StringBuilder detail = new StringBuilder();
        detail.append("按现行取费规则核算：直接费 ").append(fee.directCost().toPlainString())
                .append(" 元（其中按定额基价估算 ").append(fee.estimatedAmount().toPlainString())
                .append(" 元，可核算行占比 ").append(fee.coverage().toPlainString()).append("%）")
                .append("，人工费基数 ").append(fee.laborCost().toPlainString())
                .append(" 元，取费合计 ").append(fee.feeTotal().toPlainString())
                .append(" 元，应计合计 ").append(fee.expectedAmount().toPlainString())
                .append(" 元；送审金额 ").append(fee.settleAmount().toPlainString())
                .append(" 元，偏差 ").append(fee.deviation().multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP).toPlainString()).append("%。");
        if (weakEvidence) {
            detail.append("可核算行占比低于 80%，取费基数含估算值，证据不足，已按中风险列出，需人工确认。");
        }
        detail.append("取费明细：");
        fee.lines().forEach(line -> detail.append(line.feeName()).append("（基数 ")
                .append(line.base().toPlainString()).append(" × 费率 ")
                .append(line.rate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString())
                .append("% = ").append(line.amount().toPlainString()).append(" 元）；"));
        Issue issue = new Issue();
        issue.setProjectId(projectId);
        issue.setIssueType(Dict.ISSUE_FEE);
        issue.setLevel(level);
        issue.setRuleCode("B2-FEE-DEVIATION");
        issue.setRefType("PROJECT");
        issue.setTitle("取费金额与送审金额偏差 " + fee.deviation().multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).toPlainString() + "%");
        issue.setDetail(detail.toString());
        issue.setReviewStatus(Dict.REVIEW_PENDING);
        issues.add(issue);
    }

    /**
     * 疑点依据：由确定性内核直接给出出处（清单行/定额子目/取费规则/判定口径）。
     * aiCited 表示模型在建议里引用了该条，仅作展示标记，不影响判定。
     */
    public record IssueEvidence(String type, String code, String name, String note, String source, boolean aiCited) {
    }

    /** 模型归因结果：建议文字 + 引用的依据编号（E1/E2…） */
    private record Attribution(String advice, String model, List<String> refs) {
    }

    /** 判定口径：说明这条疑点按什么标准判出来，供复核人员核对 */
    private static final Map<String, String> RULE_CALIBERS = Map.ofEntries(
            Map.entry("B0-QTY", "工程量必须大于 0，否则无法核算"),
            Map.entry("B0-PRICE", "综合单价应大于 0；有合价时必须能取到单价"),
            Map.entry("B0-AMOUNT", "合价应等于数量×综合单价，允许 ±1 元舍入差"),
            Map.entry("B1-MISS", "清单行应套用定额子目；有单价/合价的未匹配行判高，无价行按规则库缺项判中"),
            Map.entry("B1-SEMANTIC", "语义召回命中但非精确匹配，需人工确认定额选择"),
            Map.entry("B1-CONVERT", "图纸与清单单位口径不同时按换算系数折算后比较"),
            Map.entry("B2-FEE-DEVIATION", "取费按规则库基数公式与费率核算：偏差超 5% 报中、超 15% 报高；可核算行占比低于 80% 时最高只报中"),
            Map.entry("B2-NO-RULE", "规则库缺少本工程类型的取费规则，取费核查无法执行"),
            Map.entry("B2-NO-SETTLE", "结算送审金额为空时无法比较取费偏差"),
            Map.entry("B2-NO-PRICE", "可核算行占比低于 50% 时取费基数不可信，不判定偏差"),
            Map.entry("B2-MIXED-PRICING", "混合计价项目取费只核定额法部分，不与全口径送审金额比较"),
            Map.entry("C1-NO-DRAWING", "清单材料在图纸材料表中没有对应项，需确认是否为漏画或名称口径不一致"),
            Map.entry("C1-QTY-LESS", "图纸材料表数量小于清单数量，需核对是否漏计、多计或换算口径不同"),
            Map.entry("C1-QTY-MORE", "图纸材料表数量大于清单数量，需核对是否重复计取或换算口径不同"));

    /**
     * 归因与依据：依据始终由确定性内核构造，AI 开关只决定是否追加模型建议。
     * 模型输出遵循 {"advice":"...","refs":["E1"]} 时记录引用标记；输出不合规则整段当建议，不臆造引用。
     */
    private void enrichWithAi(List<Issue> issues, Map<Long, BoqItem> itemById,
                              List<RuleQuota> quotas, List<RuleFee> fees) {
        if (issues.isEmpty()) {
            return;
        }
        Map<Issue, List<IssueEvidence>> evidenceMap = new java.util.IdentityHashMap<>();
        issues.forEach(issue -> evidenceMap.put(issue,
                buildEvidence(issue, itemById.get(issue.getBoqItemId()), quotas, fees)));

        // AI 关闭时只落依据，不调用模型：疑点依然带出处，可审计
        int budget = 50;
        List<Issue> targets = aiSettingsService.isLlmAttributionEnabled()
                ? issues.stream().limit(budget).toList() : List.of();
        Map<Issue, Attribution> attributionMap = new java.util.IdentityHashMap<>();
        if (!targets.isEmpty()) {
            // 专用小线程池并发调用 AI，避免串行累加延迟；用完即关，不占用核查线程池
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(targets.size(), 6));
            try {
                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                for (Issue issue : targets) {
                    String evidenceBlock = evidenceBlock(evidenceMap.get(issue));
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        String prompt = "请针对该造价预审疑点给出不超过 80 字的核查建议，说明应核对哪些资料与口径，不做金额判定。"
                                + "【上下文】中已给出编号依据，请只引用真正相关的编号。"
                                + "直接输出 JSON：{\"advice\":\"建议文字\",\"refs\":[\"E1\"]}，不要输出代码围栏。";
                        Optional<AiServiceClient.ChatResp> resp = aiClient.chat("ATTRIBUTION", prompt, Map.of(
                                "title", issue.getTitle(),
                                "detail", issue.getDetail() == null ? "" : issue.getDetail(),
                                "refValue", issue.getRefValue() == null ? "" : issue.getRefValue(),
                                "evidence", evidenceBlock));
                        resp.ifPresent(r -> attributionMap.put(issue, parseAttribution(r.content(), r.model())));
                    }, pool));
                }
                java.util.concurrent.CompletableFuture.allOf(
                        futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            } finally {
                pool.shutdown();
            }
        }

        for (Issue issue : issues) {
            List<IssueEvidence> evidences = evidenceMap.get(issue);
            Attribution attribution = attributionMap.get(issue);
            if (attribution != null) {
                if (attribution.advice() != null && !attribution.advice().isBlank()) {
                    issue.setAiNote(TextUtil.truncate(attribution.advice(), 900));
                }
                issue.setAiModel(attribution.model());
                evidences = markCited(evidences, attribution.refs());
            }
            issue.setAiReferences(JsonUtil.toJson(evidences));
        }
    }

    /** 依据列表：清单行 + 命中定额 + 适用取费规则 + 判定口径，全部来自确定性数据 */
    private List<IssueEvidence> buildEvidence(Issue issue, BoqItem item, List<RuleQuota> quotas,
                                              List<RuleFee> fees) {
        List<IssueEvidence> list = new ArrayList<>();
        if (item != null) {
            StringBuilder note = new StringBuilder();
            if (item.getFeatureDesc() != null && !item.getFeatureDesc().isBlank()) {
                note.append(item.getFeatureDesc()).append("；");
            }
            note.append("单位 ").append(TextUtil.nullToEmpty(item.getUnit()))
                    .append("，数量 ").append(item.getQuantity() == null ? "-" : plain(item.getQuantity()));
            if (item.getUnitPrice() != null) {
                note.append("，综合单价 ").append(plain(item.getUnitPrice())).append(" 元");
            }
            list.add(new IssueEvidence("BOQ_ROW",
                    "第 " + (item.getRowNo() == null ? "-" : item.getRowNo()) + " 行",
                    TextUtil.nullToEmpty(item.getItemName()), note.toString(), "工程量清单", false));
        }
        if (item != null && item.getMatchQuotaCode() != null) {
            RuleQuota quota = quotas.stream()
                    .filter(q -> item.getMatchQuotaCode().equals(q.getQuotaCode()))
                    .findFirst().orElse(null);
            String note = quota == null ? "" : "基价 " + plain(quota.getBasePrice()) + " 元/"
                    + TextUtil.nullToEmpty(quota.getUnit()) + "；版本 " + TextUtil.nullToEmpty(quota.getVersion());
            list.add(new IssueEvidence("QUOTA", item.getMatchQuotaCode(),
                    TextUtil.nullToEmpty(item.getMatchQuotaName()), note, "规则库-定额子目", false));
        }
        if (issue.getRuleCode() != null && issue.getRuleCode().startsWith("B2")) {
            fees.stream().limit(8).forEach(fee -> list.add(new IssueEvidence("FEE", fee.getFeeCode(),
                    fee.getFeeName(),
                    "基数 " + TextUtil.nullToEmpty(fee.getBaseFormula()) + "；费率 "
                            + (fee.getRate() == null ? "-"
                            : plain(fee.getRate().multiply(BigDecimal.valueOf(100))) + "%"),
                    "规则库-取费规则", false)));
        }
        String caliber = issue.getRuleCode() == null ? null : RULE_CALIBERS.get(issue.getRuleCode());
        if (caliber != null) {
            list.add(new IssueEvidence("RULE", issue.getRuleCode(), "判定口径", caliber, "预审规则", false));
        }
        return list;
    }

    /** 供模型阅读的依据编号清单：模型按 E1/E2… 回引 */
    private String evidenceBlock(List<IssueEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidences.size(); i++) {
            IssueEvidence e = evidences.get(i);
            sb.append("[E").append(i + 1).append("] ").append(evidenceTypeName(e.type()))
                    .append("｜").append(e.code());
            if (!e.name().isBlank()) {
                sb.append(" ").append(e.name());
            }
            if (!e.note().isBlank()) {
                sb.append("｜").append(e.note());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String evidenceTypeName(String type) {
        return switch (type) {
            case "BOQ_ROW" -> "清单行";
            case "QUOTA" -> "定额子目";
            case "FEE" -> "取费规则";
            case "RULE" -> "判定口径";
            default -> type;
        };
    }

    /** 按模型回引的编号打标；编号越界直接忽略，不猜 */
    private List<IssueEvidence> markCited(List<IssueEvidence> evidences, List<String> refs) {
        if (evidences == null || evidences.isEmpty() || refs == null || refs.isEmpty()) {
            return evidences;
        }
        java.util.Set<Integer> cited = new java.util.HashSet<>();
        for (String ref : refs) {
            Integer idx = refIndex(ref);
            if (idx != null && idx >= 1 && idx <= evidences.size()) {
                cited.add(idx);
            }
        }
        if (cited.isEmpty()) {
            return evidences;
        }
        List<IssueEvidence> marked = new ArrayList<>(evidences.size());
        for (int i = 0; i < evidences.size(); i++) {
            IssueEvidence e = evidences.get(i);
            marked.add(cited.contains(i + 1)
                    ? new IssueEvidence(e.type(), e.code(), e.name(), e.note(), e.source(), true) : e);
        }
        return marked;
    }

    private Attribution parseAttribution(String content, String model) {
        String text = stripFence(content);
        try {
            Map<String, Object> map = JsonUtil.toMap(text);
            Object advice = map.get("advice");
            List<String> refs = new ArrayList<>();
            if (map.get("refs") instanceof List<?> raw) {
                raw.forEach(item -> refs.add(String.valueOf(item)));
            }
            return new Attribution(advice == null ? text : String.valueOf(advice), model, refs);
        } catch (Exception e) {
            // 模型没按 JSON 输出：整段当建议，不臆造引用
            return new Attribution(text, model, List.of());
        }
    }

    /** 去掉模型常带的 ```json 围栏 */
    private String stripFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            text = firstLine < 0 ? "" : text.substring(firstLine + 1);
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    private Integer refIndex(String ref) {
        if (ref == null) {
            return null;
        }
        String digits = ref.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private Issue issue(Long projectId, BoqItem item, String rowRef, String type, int level,
                        String ruleCode, String title, String detail) {
        Issue issue = new Issue();
        issue.setProjectId(projectId);
        issue.setBoqItemId(item.getId());
        issue.setIssueType(type);
        issue.setLevel(level);
        issue.setRuleCode(ruleCode);
        issue.setRuleId(null);
        issue.setRefType("BOQ_ROW");
        issue.setRefValue(rowRef);
        issue.setTitle(title);
        issue.setDetail(TextUtil.truncate(detail, 4000));
        issue.setReviewStatus(Dict.REVIEW_PENDING);
        return issue;
    }

    private void updateProjectStatus(Long projectId, String target) {
        try {
            projectService.transit(projectId, target);
        } catch (Exception e) {
            Project project = projectMapper.selectById(projectId);
            if (project != null) {
                project.setStatus(target);
                projectMapper.updateById(project);
            }
        }
    }

    private void fail(Long taskId, String message) {
        AsyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(Dict.TASK_FAILED);
        task.setMessage(TextUtil.truncate(message, 900));
        taskMapper.updateById(task);
        if (task.getProjectId() != null) {
            updateProjectStatus(task.getProjectId(), Dict.PROJ_IMPORTED);
        }
    }
}
