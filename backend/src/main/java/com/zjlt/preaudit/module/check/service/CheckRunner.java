package com.zjlt.preaudit.module.check.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.ai.client.AiServiceClient;
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
 * 说明：全部判定为确定性规则；LLM 仅生成归因解释（ai_note），不参与金额与结论。
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

    @Value("${preaudit.check.amount-tolerance:1.00}")
    private BigDecimal amountTolerance;

    @Value("${preaudit.check.fee-deviation-threshold:0.05}")
    private BigDecimal feeThreshold;

    @Value("${preaudit.check.fee-deviation-high-threshold:0.15}")
    private BigDecimal feeHighThreshold;

    @Value("${preaudit.check.llm-attribution-enabled:false}")
    private boolean llmAttributionEnabled;

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
        List<BoqItem> items = boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                .eq(BoqItem::getProjectId, project.getId())
                .orderByAsc(BoqItem::getRowNo));
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
        collectFeeIssues(project.getId(), feeResult, fees, issues);

        // B3 图纸-清单核对：用竣工图解析出的材料表跟清单比量（没有图纸解析结果时自动跳过）
        task.setProgress(88);
        task.setMessage("正在核对图纸材料与清单");
        taskMapper.updateById(task);
        issues.addAll(drawingCheckService.check(project, items));

        if (llmAttributionEnabled) {
            enrichWithAi(issues);
        }
        issues.forEach(issueMapper::insert);

        int high = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_HIGH).count();
        int medium = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_MEDIUM).count();
        int low = (int) issues.stream().filter(x -> x.getLevel() == Dict.LEVEL_LOW).count();
        BigDecimal hitRate = quotaRows == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(hitTop3 * 100.0 / quotaRows).setScale(2, RoundingMode.HALF_UP);

        CheckDtos.CheckSummary summary = new CheckDtos.CheckSummary(items.size(), issues.size(), high, medium, low,
                hitRate, feeResult.directCost(), feeResult.feeTotal(), feeResult.expectedAmount(),
                feeResult.settleAmount(), feeResult.deviation(), System.currentTimeMillis() - start);

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
                issues.add(issue(projectId, item, rowRef, Dict.ISSUE_QUOTA, Dict.LEVEL_HIGH, "B1-MISS",
                        "未匹配到定额子目（错套/缺套风险）",
                        where + " 清单未填定额编号，" + match.message() + "。候选：" + candidates
                                + "。建议补填定额编号，或在「规则库-定额子目」补充后再核查。"));
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

    private void collectFeeIssues(Long projectId, CheckDtos.FeeResult fee, List<RuleFee> fees, List<Issue> issues) {
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

    /** LLM 归因（仅对高风险疑点、仅生成解释，不影响金额与结论） */
    private void enrichWithAi(List<Issue> issues) {
        int budget = 10;
        for (Issue issue : issues) {
            if (budget <= 0) {
                break;
            }
            if (issue.getLevel() != Dict.LEVEL_HIGH) {
                continue;
            }
            budget--;
            String prompt = "请针对该造价预审疑点给出不超过 80 字的核查建议，说明应核对哪些资料与口径，不做金额判定。";
            Optional<AiServiceClient.ChatResp> resp = aiClient.chat("ATTRIBUTION", prompt, Map.of(
                    "title", issue.getTitle(),
                    "detail", issue.getDetail() == null ? "" : issue.getDetail(),
                    "refValue", issue.getRefValue() == null ? "" : issue.getRefValue()));
            resp.ifPresent(r -> {
                issue.setAiNote(TextUtil.truncate(r.content(), 900));
                issue.setAiModel(r.model());
            });
        }
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
