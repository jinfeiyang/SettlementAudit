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
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import com.zjlt.preaudit.module.rule.mapper.RuleQuotaMapper;
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

        Map<String, RuleQuota> quotaByName = loadQuotaIndex();
        List<BoqItem> items = new ArrayList<>(outcome.rows().size());
        Map<String, Integer> engTypeCount = new HashMap<>();
        for (BoqExcelParser.Row row : outcome.rows()) {
            BoqItem item = new BoqItem();
            item.setProjectId(projectId);
            item.setSourceFileId(fileId);
            item.setRowNo(row.rowNo());
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
            // 一级精确匹配：名称+单位一致即认定标准化成功
            RuleQuota exact = quotaByName.get(quotaKey(stdName, row.unit()));
            if (exact == null) {
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
            }
            items.add(item);
        }
        items.forEach(boqItemMapper::insert);

        String projectEngType = engTypeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(project.getEngType());
        if (!StringUtils.hasText(project.getEngType()) && projectEngType != null) {
            project.setEngType(projectEngType);
        }
        if (!Dict.PROJ_CHECKING.equals(project.getStatus())) {
            project.setStatus(Dict.PROJ_IMPORTED);
        }
        projectMapper.updateById(project);

        BigDecimal rate = BigDecimal.valueOf(outcome.parseRate()).setScale(2, RoundingMode.HALF_UP);
        String message = outcome.summary() + (outcome.parseRate() < 95
                ? "；可解析率低于 95%，建议按模板整理表头后重传" : "");
        log.info("项目 {} 清单导入完成：{} 行，可解析率 {}%", projectId, items.size(), rate);
        return new BoqDtos.ImportResult(fileId, outcome.candidateRows(), items.size(), rate,
                project.getEngType(), Dict.engType(project.getEngType()), message);
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

    private Map<String, RuleQuota> loadQuotaIndex() {
        List<RuleQuota> quotas = ruleQuotaMapper.selectList(Wrappers.<RuleQuota>lambdaQuery()
                .eq(RuleQuota::getStatus, 1));
        Map<String, RuleQuota> map = new HashMap<>();
        for (RuleQuota q : quotas) {
            String name = TextUtil.normalize(q.getQuotaName());
            map.putIfAbsent(quotaKey(name, q.getUnit()), q);
            map.putIfAbsent(quotaKey(name, null), q);
        }
        return map;
    }

    private String quotaKey(String normalizedName, String unit) {
        return normalizedName + "|" + (unit == null ? "" : TextUtil.normalizeUnit(unit));
    }

    private BoqDtos.ItemVO toVO(BoqItem i) {
        return new BoqDtos.ItemVO(i.getId(), i.getProjectId(), i.getRowNo(), i.getItemCode(), i.getItemName(),
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
