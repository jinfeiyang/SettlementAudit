package com.zjlt.preaudit.module.boq.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.boq.dto.BoqDtos;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqItemMapper;
import com.zjlt.preaudit.module.boq.parser.BoqExcelParser;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.check.service.FeeCheckService;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import com.zjlt.preaudit.module.rule.mapper.RuleQuotaMapper;
import com.zjlt.preaudit.module.rule.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程量清单导入与标准化（A2）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoqService {

    private final BoqItemMapper boqItemMapper;
    private final ProjectMapper projectMapper;
    private final RuleQuotaMapper ruleQuotaMapper;
    private final IssueMapper issueMapper;
    private final BoqExcelParser parser;
    private final RuleService ruleService;
    private final FeeCheckService feeCheckService;

    /** 从 Excel 导入清单：解析 → 标准化 → 落库 → 重置核查结论 */
    @Transactional
    public BoqDtos.ImportResult importFromFile(Long projectId, Long fileId, InputStream in, String filename) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(404, "项目不存在：" + projectId);
        }
        BoqExcelParser.ParseOutcome outcome = parser.parse(in, filename);
        // 重新导入视为清单整体替换：清理历史清单项与疑点（结论不可追溯残留）
        boqItemMapper.delete(Wrappers.<BoqItem>lambdaQuery().eq(BoqItem::getProjectId, projectId));
        issueMapper.delete(Wrappers.<Issue>lambdaQuery().eq(Issue::getProjectId, projectId));

        if (outcome.rows().isEmpty()) {
            return new BoqDtos.ImportResult(fileId, outcome.candidateRows(), 0, BigDecimal.ZERO,
                    project.getEngType(), Dict.engType(project.getEngType()), outcome.message());
        }

        List<RuleQuota> quotas = ruleQuotaMapper.selectList(Wrappers.<RuleQuota>lambdaQuery()
                .eq(RuleQuota::getStatus, 1));
        Map<String, RuleQuota> quotaByName = indexQuotaByName(quotas);
        Map<String, RuleQuota> quotaByCode = indexQuotaByCode(quotas);
        List<BoqItem> items = new ArrayList<>(outcome.rows().size());
        Map<String, Integer> engTypeCount = new HashMap<>();
        for (BoqExcelParser.Row row : outcome.rows()) {
            BoqItem item = new BoqItem();
            item.setProjectId(projectId);
            item.setSourceFileId(fileId);
            item.setRowNo(row.rowNo());
            item.setItemType(row.itemType());
            item.setSourceSheet(row.sourceSheet());
            item.setItemCode(row.itemCode());
            item.setItemName(TextUtil.truncate(row.itemName(), 250));
            item.setFeatureDesc(row.featureDesc());
            item.setUnit(row.unit());
            item.setQuantity(row.quantity());
            item.setUnitPrice(row.unitPrice());
            item.setTotalPrice(row.totalPrice());
            String stdName = TextUtil.normalize(row.itemName());
            item.setStdItemName(TextUtil.truncate(stdName, 250));
            String engType = TextUtil.guessEngType(row.itemName(), row.featureDesc(), null);
            item.setEngType(engType);
            if (engType != null) {
                engTypeCount.merge(engType, 1, Integer::sum);
            }
            // 一级精确匹配：名称+单位一致即认定标准化成功。
            // 材料/甲供物资行不参与定额匹配（它们的名称是物资名，套不到定额子目上）
            RuleQuota exact = Dict.BOQ_QUOTA.equals(row.itemType())
                    ? quotaByName.get(quotaKey(stdName, row.unit()))
                    : null;
            if (exact == null && Dict.BOQ_QUOTA.equals(row.itemType())) {
                exact = quotaByName.get(quotaKey(stdName, null));
            }
            if (exact != null) {
                item.setStdItemCode(exact.getQuotaCode());
                item.setStdItemName(exact.getQuotaName());
                item.setMatchQuotaCode(exact.getQuotaCode());
                item.setMatchQuotaName(exact.getQuotaName());
                item.setMatchScore(new BigDecimal("1.0000"));
                item.setMatchLevel(1);
                if (item.getEngType() == null) {
                    item.setEngType(exact.getEngType());
                }
            } else if (Dict.BOQ_QUOTA.equals(row.itemType()) && TextUtil.isQuotaCode(row.itemCode())) {
                // 清单本身带定额编号（表三甲那类）：以清单为准，认定为已套用，
                // 规则库里有同编号就把定额名称补上，没有也照样算已套用，避免核查时误报缺套。
                String code = row.itemCode().trim();
                RuleQuota byCode = quotaByCode.get(TextUtil.normalize(code));
                item.setStdItemCode(code);
                item.setMatchQuotaCode(code);
                item.setMatchScore(new BigDecimal("1.0000"));
                item.setMatchLevel(1);
                if (byCode != null) {
                    item.setStdItemName(TextUtil.truncate(byCode.getQuotaName(), 250));
                    item.setMatchQuotaName(byCode.getQuotaName());
                    if (item.getEngType() == null) {
                        item.setEngType(byCode.getEngType());
                    }
                }
            }
            items.add(item);
        }
        items.forEach(boqItemMapper::insert);
        // 按当前生效的取费规则生成费用行，作为清单的一部分落库，使「清单合计」= 直接费 + 取费
        int feeRows = appendFeeRows(project, projectId, fileId, items, quotas);
        // 表二定额法建安费基准齐全时，补齐我们未覆盖的材料/机械/规费差额，使清单合计与送审对齐
        int diffRows = appendQuotaDiffRow(projectId, fileId, items, outcome.quotaBuildTotal());

        String projectEngType = engTypeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(project.getEngType());
        if (!StringUtils.hasText(project.getEngType()) && projectEngType != null) {
            project.setEngType(projectEngType);
        }
        if (!Dict.PROJ_CHECKING.equals(project.getStatus())) {
            project.setStatus(Dict.PROJ_IMPORTED);
        }
        projectMapper.updateById(project);

        String typeCounts = items.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> Dict.boqItemType(item.getItemType()), java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + " 条")
                .collect(java.util.stream.Collectors.joining("、"));
        BigDecimal rate = BigDecimal.valueOf(outcome.parseRate()).setScale(2, RoundingMode.HALF_UP);
        String message = outcome.summary() + (typeCounts.isEmpty() ? "" : "（" + typeCounts + "）")
                + (feeRows > 0 ? "；按取费规则生成费用行 " + feeRows + " 条" : "")
                + (diffRows > 0 ? "；定额法补差 1 条" : "")
                + (outcome.parseRate() < 95
                ? "；可解析率低于 95%，建议按模板整理表头后重传" : "");
        log.info("项目 {} 清单导入完成：{} 行 + {} 条取费行 + {} 条补差行，可解析率 {}%",
                projectId, items.size(), feeRows, diffRows, rate);
        return new BoqDtos.ImportResult(fileId, outcome.candidateRows(), items.size() + feeRows + diffRows, rate,
                project.getEngType(), Dict.engType(project.getEngType()), message);
    }

    /**
     * 表二定额法建安费（除税）含直接费、材料费、机械、措施、间接费、利润等完整造价。
     * 我们按工日折算的直接费 + 规则取费通常只覆盖其中一部分，差额补一条 OTHER 行，
     * 使清单合计与决算送审金额对齐。该行不参与定额匹配与取费（OTHER 已在 isPricedExtra 中排除）。
     */
    private int appendQuotaDiffRow(Long projectId, Long fileId, List<BoqItem> items, BigDecimal quotaBuildTotal) {
        if (quotaBuildTotal == null || quotaBuildTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal covered = items.stream()
                .filter(i -> Dict.BOQ_QUOTA.equals(i.getItemType()))
                .map(i -> i.getTotalPrice() == null ? BigDecimal.ZERO : i.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 取费行：按 feeCode 前缀无关，直接统计本次导入项目下已落库的 FEE 行
        BigDecimal feeTotal = boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                        .eq(BoqItem::getProjectId, projectId).eq(BoqItem::getItemType, Dict.BOQ_FEE))
                .stream()
                .map(i -> i.getTotalPrice() == null ? BigDecimal.ZERO : i.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = quotaBuildTotal.subtract(covered).subtract(feeTotal)
                .setScale(2, RoundingMode.HALF_UP);
        if (diff.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        // 取费行在上面已经插到清单行之后（占用 maxRowNo+1 起的行号），
        // 补差行必须取项目内现有最大行号续排，否则会和费用行撞号
        int maxRowNo = boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                        .eq(BoqItem::getProjectId, projectId))
                .stream().map(BoqItem::getRowNo)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo)
                .orElse(items.size());
        BoqItem diffItem = new BoqItem();
        diffItem.setProjectId(projectId);
        diffItem.setSourceFileId(fileId);
        diffItem.setRowNo(maxRowNo + 1);
        diffItem.setItemType(Dict.BOQ_OTHER);
        diffItem.setSourceSheet("表二");
        diffItem.setItemCode("QUOTA-DIFF");
        diffItem.setItemName("定额法造价补差");
        // 文案带等号与结果，避免只看到三个减数、算不出补差是怎么来的
        diffItem.setFeatureDesc("表二定额法建安费 " + quotaBuildTotal.toPlainString()
                + " 元 − 已计定额直接费 " + covered.toPlainString()
                + " 元 − 定额法取费 " + feeTotal.toPlainString()
                + " 元 = 补差 " + diff.toPlainString() + " 元");
        diffItem.setUnit("元");
        diffItem.setQuantity(BigDecimal.ONE);
        diffItem.setUnitPrice(diff);
        diffItem.setTotalPrice(diff);
        diffItem.setEngType(items.get(0).getEngType());
        boqItemMapper.insert(diffItem);
        return 1;
    }

    /**
     * 依据当前启用的取费规则，在清单落库后生成费用行。
     * 复用 FeeCheckService 的核算口径（直接费/人工费/基数×费率），保证与核查报告一致；
     * 这些行标记为 FEE，核查内核会跳过，避免把费用行当成清单行再套定额或重复计费。
     */
    private int appendFeeRows(Project project, Long projectId, Long fileId,
                              List<BoqItem> items, List<RuleQuota> quotas) {
        List<RuleFee> fees = ruleService.listEnabledFees(project.getEngType());
        if (fees.isEmpty()) {
            return 0;
        }
        CheckDtos.FeeResult result = feeCheckService.check(project, items, quotas, fees);
        int maxRowNo = items.stream().map(BoqItem::getRowNo)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(items.size());
        int created = 0;
        for (CheckDtos.FeeLine line : result.lines()) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BoqItem feeItem = new BoqItem();
            feeItem.setProjectId(projectId);
            feeItem.setSourceFileId(fileId);
            feeItem.setRowNo(++maxRowNo);
            feeItem.setItemType(Dict.BOQ_FEE);
            feeItem.setSourceSheet("取费规则");
            feeItem.setItemCode(line.feeCode());
            feeItem.setItemName(TextUtil.truncate(line.feeName(), 250));
            feeItem.setFeatureDesc(line.baseFormula() + "（基数 " + line.base().toPlainString() + " × 费率 "
                    + line.rate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%）");
            feeItem.setUnit("元");
            feeItem.setQuantity(BigDecimal.ONE);
            feeItem.setUnitPrice(line.amount());
            feeItem.setTotalPrice(line.amount());
            feeItem.setEngType(project.getEngType());
            boqItemMapper.insert(feeItem);
            created++;
        }
        return created;
    }

    public PageResult<BoqDtos.ItemVO> page(Long projectId, String keyword, long page, long size) {
        Page<BoqItem> p = new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size)));
        var wrapper = Wrappers.<BoqItem>lambdaQuery()
                .eq(BoqItem::getProjectId, projectId)
                .orderByAsc(BoqItem::getRowNo);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(q -> q.like(BoqItem::getItemName, kw)
                    .or().like(BoqItem::getItemCode, kw)
                    .or().like(BoqItem::getFeatureDesc, kw));
        }
        boqItemMapper.selectPage(p, wrapper);
        return PageResult.of(p, this::toVO);
    }

    public List<BoqDtos.ItemVO> listAll(Long projectId) {
        return boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                        .eq(BoqItem::getProjectId, projectId).orderByAsc(BoqItem::getRowNo))
                .stream().map(this::toVO).toList();
    }

    /** 行号回溯定位（D2）：疑点 → 清单行 */
    public BoqDtos.ItemVO getByRow(Long projectId, Integer rowNo) {
        BoqItem item = boqItemMapper.selectOne(Wrappers.<BoqItem>lambdaQuery()
                .eq(BoqItem::getProjectId, projectId)
                .eq(BoqItem::getRowNo, rowNo)
                .orderByAsc(BoqItem::getId)
                .last("LIMIT 1"));
        return item == null ? null : toVO(item);
    }

    @Transactional
    public void clear(Long projectId) {
        boqItemMapper.delete(Wrappers.<BoqItem>lambdaQuery().eq(BoqItem::getProjectId, projectId));
        issueMapper.delete(Wrappers.<Issue>lambdaQuery().eq(Issue::getProjectId, projectId));
    }

    public int count(Long projectId) {
        Long c = boqItemMapper.selectCount(Wrappers.<BoqItem>lambdaQuery().eq(BoqItem::getProjectId, projectId));
        return c == null ? 0 : c.intValue();
    }

    private Map<String, RuleQuota> indexQuotaByName(List<RuleQuota> quotas) {
        Map<String, RuleQuota> map = new HashMap<>();
        for (RuleQuota q : quotas) {
            String name = TextUtil.normalize(q.getQuotaName());
            map.putIfAbsent(quotaKey(name, q.getUnit()), q);
            map.putIfAbsent(quotaKey(name, null), q);
        }
        return map;
    }

    /** 定额编号索引：清单自带编号时用来补定额名称 */
    private Map<String, RuleQuota> indexQuotaByCode(List<RuleQuota> quotas) {
        Map<String, RuleQuota> map = new HashMap<>();
        for (RuleQuota q : quotas) {
            if (q.getQuotaCode() != null) {
                map.putIfAbsent(TextUtil.normalize(q.getQuotaCode()), q);
            }
        }
        return map;
    }

    private String quotaKey(String normalizedName, String unit) {
        return normalizedName + "|" + (unit == null ? "" : TextUtil.normalizeUnit(unit));
    }

    private BoqDtos.ItemVO toVO(BoqItem i) {
        return new BoqDtos.ItemVO(i.getId(), i.getProjectId(), i.getRowNo(), i.getItemType(),
                Dict.boqItemType(i.getItemType()), i.getSourceSheet(), i.getItemCode(), i.getItemName(),
                i.getFeatureDesc(), i.getUnit(), i.getQuantity(), i.getUnitPrice(), i.getTotalPrice(),
                i.getStdItemCode(), i.getStdItemName(), i.getEngType(), i.getMatchQuotaCode(),
                i.getMatchQuotaName(), i.getMatchScore(), i.getMatchLevel());
    }

    /** 清单导入模板（供前端下载） */
    public static List<String> templateHeaders() {
        return List.of("序号", "项目编码", "项目名称", "项目特征", "计量单位", "工程量", "综合单价", "合价");
    }

    public static List<List<String>> templateRows() {
        return List.of(
                List.of("1", "TXL1-001", "敷设室外光缆（架空）", "GYTA53-24芯 架空敷设", "百米", "12.5", "320", "4000"),
                List.of("2", "TYS2-011", "安装室内通信设备（机架式）", "机架式安装，含固定", "台", "8", "150", "1200"));
    }

    public static String templateName() {
        return "工程量清单导入模板.xlsx";
    }

    public static String templateSheet() {
        return "清单模板";
    }
}
