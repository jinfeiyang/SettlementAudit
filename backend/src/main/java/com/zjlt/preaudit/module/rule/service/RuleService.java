package com.zjlt.preaudit.module.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.ExcelUtil;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.rule.dto.RuleDtos;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import com.zjlt.preaudit.module.rule.mapper.RuleFeeMapper;
import com.zjlt.preaudit.module.rule.mapper.RuleQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 定额规则与取费标准库维护（A3）：CRUD + Excel 导入 + 模板下载
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleQuotaMapper quotaMapper;
    private final RuleFeeMapper feeMapper;

    // ---------------- 定额子目 ----------------

    public PageResult<RuleDtos.QuotaVO> quotaPage(String keyword, String engType, long page, long size) {
        Page<RuleQuota> p = new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size)));
        LambdaQueryWrapper<RuleQuota> w = Wrappers.<RuleQuota>lambdaQuery()
                .eq(StringUtils.hasText(engType), RuleQuota::getEngType, engType)
                .orderByAsc(RuleQuota::getQuotaCode);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(RuleQuota::getQuotaName, kw)
                    .or().like(RuleQuota::getQuotaCode, kw)
                    .or().like(RuleQuota::getFeatureKeys, kw));
        }
        quotaMapper.selectPage(p, w);
        return PageResult.of(p, this::toVO);
    }

    @Transactional
    public RuleDtos.QuotaVO saveQuota(RuleDtos.QuotaReq req) {
        RuleQuota quota = new RuleQuota();
        applyQuota(quota, req);
        quota.setVersion(defaultVersion(req.version()));
        Long dup = quotaMapper.selectCount(Wrappers.<RuleQuota>lambdaQuery()
                .eq(RuleQuota::getQuotaCode, quota.getQuotaCode())
                .eq(RuleQuota::getVersion, quota.getVersion()));
        if (dup != null && dup > 0) {
            throw new BizException("定额子目「" + quota.getQuotaCode() + "」版本 " + quota.getVersion() + " 已存在");
        }
        quotaMapper.insert(quota);
        return toVO(quota);
    }

    @Transactional
    public RuleDtos.QuotaVO updateQuota(Long id, RuleDtos.QuotaReq req) {
        RuleQuota quota = quotaMapper.selectById(id);
        if (quota == null) {
            throw new BizException(404, "定额子目不存在");
        }
        applyQuota(quota, req);
        quota.setVersion(defaultVersion(req.version()));
        quotaMapper.updateById(quota);
        return toVO(quota);
    }

    @Transactional
    public void deleteQuota(Long id) {
        quotaMapper.deleteById(id);
    }

    /** 生效定额列表（核查内核使用） */
    public List<RuleQuota> listEnabledQuotas() {
        return quotaMapper.selectList(Wrappers.<RuleQuota>lambdaQuery().eq(RuleQuota::getStatus, 1));
    }

    @Transactional
    public RuleDtos.ImportResult importQuotas(InputStream in, String filename) {
        List<List<String>> rows = ExcelUtil.readRows(in, filename);
        int headerRow = ExcelUtil.findHeaderRow(rows, List.of("定额编号", "定额名称", "基价", "单位"), 10);
        if (headerRow < 0) {
            throw new BizException("未识别定额表头（需包含：定额编号/定额名称/单位/基价/特征关键词/工程类型）");
        }
        List<String> header = rows.get(headerRow);
        int idxCode = ExcelUtil.findColumn(header, "定额编号", "子目编号", "编号");
        int idxName = ExcelUtil.findColumn(header, "定额名称", "子目名称", "名称");
        int idxUnit = ExcelUtil.findColumn(header, "单位");
        int idxBase = ExcelUtil.findColumn(header, "基价", "单价");
        int idxLabor = ExcelUtil.findColumn(header, "人工费");
        int idxKeys = ExcelUtil.findColumn(header, "特征关键词", "关键词", "特征");
        int idxEng = ExcelUtil.findColumn(header, "工程类型", "专业");
        int idxVersion = ExcelUtil.findColumn(header, "版本");
        int idxDate = ExcelUtil.findColumn(header, "生效日期", "执行日期");
        if (idxCode < 0 || idxName < 0) {
            throw new BizException("定额表头缺少「定额编号/定额名称」列");
        }
        int imported = 0;
        int skipped = 0;
        int candidates = 0;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String code = ExcelUtil.cell(row, idxCode);
            String name = ExcelUtil.cell(row, idxName);
            if (code == null && name == null) {
                continue;
            }
            candidates++;
            if (code == null || name == null) {
                skipped++;
                continue;
            }
            RuleQuota quota = new RuleQuota();
            quota.setQuotaCode(code);
            quota.setQuotaName(name);
            quota.setUnit(ExcelUtil.cell(row, idxUnit));
            quota.setBasePrice(TextUtil.firstNumber(ExcelUtil.cell(row, idxBase)));
            quota.setLaborPrice(TextUtil.firstNumber(ExcelUtil.cell(row, idxLabor)));
            quota.setFeatureKeys(JsonUtil.toJson(splitKeys(ExcelUtil.cell(row, idxKeys))));
            quota.setEngType(normalizeEngType(ExcelUtil.cell(row, idxEng)));
            quota.setVersion(excelVersion(ExcelUtil.cell(row, idxVersion)));
            quota.setEffectiveDate(parseDate(ExcelUtil.cell(row, idxDate)));
            quota.setStatus(1);
            RuleQuota exist = quotaMapper.selectOne(Wrappers.<RuleQuota>lambdaQuery()
                    .eq(RuleQuota::getQuotaCode, quota.getQuotaCode())
                    .eq(RuleQuota::getVersion, quota.getVersion())
                    .last("LIMIT 1"));
            if (exist == null) {
                quotaMapper.insert(quota);
            } else {
                quota.setId(exist.getId());
                quotaMapper.updateById(quota);
            }
            imported++;
        }
        log.info("定额规则导入：候选 {} 行，成功 {} 行", candidates, imported);
        return new RuleDtos.ImportResult(candidates, imported, skipped,
                String.format(Locale.ROOT, "共 %d 行，导入/更新 %d 条，跳过 %d 行", candidates, imported, skipped));
    }

    // ---------------- 取费规则 ----------------

    public PageResult<RuleDtos.FeeVO> feePage(String keyword, String engType, long page, long size) {
        Page<RuleFee> p = new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size)));
        LambdaQueryWrapper<RuleFee> w = Wrappers.<RuleFee>lambdaQuery()
                .eq(StringUtils.hasText(engType), RuleFee::getEngType, engType)
                .orderByAsc(RuleFee::getFeeCode);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(RuleFee::getFeeName, kw).or().like(RuleFee::getFeeCode, kw)
                    .or().like(RuleFee::getEngCategory, kw));
        }
        feeMapper.selectPage(p, w);
        return PageResult.of(p, this::toVO);
    }

    @Transactional
    public RuleDtos.FeeVO saveFee(RuleDtos.FeeReq req) {
        RuleFee fee = new RuleFee();
        applyFee(fee, req);
        fee.setVersion(defaultVersion(req.version()));
        feeMapper.insert(fee);
        return toVO(fee);
    }

    @Transactional
    public RuleDtos.FeeVO updateFee(Long id, RuleDtos.FeeReq req) {
        RuleFee fee = feeMapper.selectById(id);
        if (fee == null) {
            throw new BizException(404, "取费规则不存在");
        }
        applyFee(fee, req);
        fee.setVersion(defaultVersion(req.version()));
        feeMapper.updateById(fee);
        return toVO(fee);
    }

    @Transactional
    public void deleteFee(Long id) {
        feeMapper.deleteById(id);
    }

    /** 工程类型适用的取费规则（核查内核使用） */
    public List<RuleFee> listEnabledFees(String engType) {
        return feeMapper.selectList(Wrappers.<RuleFee>lambdaQuery()
                .eq(RuleFee::getStatus, 1)
                .and(StringUtils.hasText(engType), q -> q.eq(RuleFee::getEngType, engType)
                        .or().isNull(RuleFee::getEngType)));
    }

    @Transactional
    public RuleDtos.ImportResult importFees(InputStream in, String filename) {
        List<List<String>> rows = ExcelUtil.readRows(in, filename);
        int headerRow = ExcelUtil.findHeaderRow(rows, List.of("费用项编码", "费用项名称", "费率", "取费基数"), 10);
        if (headerRow < 0) {
            throw new BizException("未识别取费表头（需包含：费用项编码/费用项名称/取费基数公式/费率/工程类别）");
        }
        List<String> header = rows.get(headerRow);
        int idxCode = ExcelUtil.findColumn(header, "费用项编码", "费用编码", "编码");
        int idxName = ExcelUtil.findColumn(header, "费用项名称", "费用名称", "名称");
        int idxFormula = ExcelUtil.findColumn(header, "取费基数", "基数");
        int idxRate = ExcelUtil.findColumn(header, "费率");
        int idxCategory = ExcelUtil.findColumn(header, "工程类别", "类别");
        int idxEng = ExcelUtil.findColumn(header, "工程类型");
        int idxVersion = ExcelUtil.findColumn(header, "版本");
        int idxDate = ExcelUtil.findColumn(header, "生效日期", "执行日期");
        if (idxCode < 0 || idxName < 0) {
            throw new BizException("取费表头缺少「费用项编码/费用项名称」列");
        }
        int imported = 0;
        int skipped = 0;
        int candidates = 0;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String code = ExcelUtil.cell(row, idxCode);
            String name = ExcelUtil.cell(row, idxName);
            if (code == null && name == null) {
                continue;
            }
            candidates++;
            if (code == null || name == null) {
                skipped++;
                continue;
            }
            RuleFee fee = new RuleFee();
            fee.setFeeCode(code);
            fee.setFeeName(name);
            fee.setBaseFormula(ExcelUtil.cell(row, idxFormula));
            fee.setRate(normalizeRate(TextUtil.firstNumber(ExcelUtil.cell(row, idxRate))));
            fee.setEngCategory(ExcelUtil.cell(row, idxCategory));
            fee.setEngType(normalizeEngType(ExcelUtil.cell(row, idxEng)));
            fee.setVersion(excelVersion(ExcelUtil.cell(row, idxVersion)));
            fee.setEffectiveDate(parseDate(ExcelUtil.cell(row, idxDate)));
            fee.setStatus(1);
            RuleFee exist = feeMapper.selectOne(Wrappers.<RuleFee>lambdaQuery()
                    .eq(RuleFee::getFeeCode, fee.getFeeCode())
                    .eq(RuleFee::getEngCategory, fee.getEngCategory() == null ? "" : fee.getEngCategory())
                    .eq(RuleFee::getVersion, fee.getVersion())
                    .last("LIMIT 1"));
            if (exist == null) {
                feeMapper.insert(fee);
            } else {
                fee.setId(exist.getId());
                feeMapper.updateById(fee);
            }
            imported++;
        }
        return new RuleDtos.ImportResult(candidates, imported, skipped,
                String.format(Locale.ROOT, "共 %d 行，导入/更新 %d 条，跳过 %d 行", candidates, imported, skipped));
    }

    // ---------------- 模板 ----------------

    public List<String> quotaTemplateHeaders() {
        return List.of("定额编号", "定额名称", "单位", "基价", "人工费", "特征关键词", "工程类型", "版本", "生效日期");
    }

    public List<List<String>> quotaTemplateRows() {
        return List.of(
                List.of("TXL1-001", "敷设室外光缆（架空）", "百米", "320", "120", "光缆/架空/敷设", "LINE", "v1", "2026-01-01"));
    }

    public List<String> feeTemplateHeaders() {
        return List.of("费用项编码", "费用项名称", "取费基数公式", "费率", "工程类别", "工程类型", "版本", "生效日期");
    }

    public List<List<String>> feeTemplateRows() {
        return List.of(
                List.of("F01", "措施项目费", "人工费*1.0", "5%", "通信线路", "LINE", "v1", "2026-01-01"));
    }

    // ---------------- 私有工具 ----------------

    private void applyQuota(RuleQuota quota, RuleDtos.QuotaReq req) {
        quota.setQuotaCode(req.quotaCode().trim());
        quota.setQuotaName(req.quotaName().trim());
        quota.setUnit(req.unit());
        quota.setBasePrice(req.basePrice());
        quota.setLaborPrice(req.laborPrice());
        quota.setFeatureKeys(JsonUtil.toJson(req.featureKeys() == null ? List.of() : req.featureKeys()));
        quota.setEngType(normalizeEngType(req.engType()));
        quota.setEffectiveDate(req.effectiveDate());
        quota.setStatus(req.status() == null ? 1 : req.status());
    }

    private void applyFee(RuleFee fee, RuleDtos.FeeReq req) {
        fee.setFeeCode(req.feeCode().trim());
        fee.setFeeName(req.feeName().trim());
        fee.setBaseFormula(req.baseFormula());
        fee.setRate(normalizeRate(req.rate()));
        fee.setEngCategory(req.engCategory());
        fee.setEngType(normalizeEngType(req.engType()));
        fee.setEffectiveDate(req.effectiveDate());
        fee.setStatus(req.status() == null ? 1 : req.status());
    }

    private RuleDtos.QuotaVO toVO(RuleQuota q) {
        return new RuleDtos.QuotaVO(q.getId(), q.getQuotaCode(), q.getQuotaName(), q.getUnit(), q.getBasePrice(),
                q.getLaborPrice(), JsonUtil.toStringList(q.getFeatureKeys()), q.getEngType(),
                Dict.engType(q.getEngType()), q.getVersion(), q.getEffectiveDate(), q.getStatus());
    }

    private RuleDtos.FeeVO toVO(RuleFee f) {
        return new RuleDtos.FeeVO(f.getId(), f.getFeeCode(), f.getFeeName(), f.getBaseFormula(), f.getRate(),
                f.getEngCategory(), f.getEngType(), f.getVersion(), f.getEffectiveDate(), f.getStatus());
    }

    private List<String> splitKeys(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return new ArrayList<>(Arrays.stream(raw.split("[,，/、;；\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private String normalizeEngType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.contains("LINE") || v.contains("线路")) {
            return Dict.ENG_LINE;
        }
        if (v.contains("DEVICE") || v.contains("设备")) {
            return Dict.ENG_DEVICE;
        }
        return null;
    }

    private BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        // 支持 5% / 5 / 0.05 三种写法
        return rate.compareTo(BigDecimal.ONE) > 0 ? rate.movePointLeft(2) : rate;
    }

    private String defaultVersion(String version) {
        return StringUtils.hasText(version) ? version.trim() : "v1";
    }

    private String excelVersion(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : "v1";
    }

    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String v = raw.trim().replace("/", "-");
            if (v.length() >= 10) {
                v = v.substring(0, 10);
            }
            return LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean existsQuota(Long id) {
        return id != null && quotaMapper.selectById(id) != null;
    }

    public String quotaCodeOf(Long id) {
        if (id == null) {
            return null;
        }
        RuleQuota q = quotaMapper.selectById(id);
        return q == null ? null : q.getQuotaCode();
    }

    public static boolean sameVersion(String a, String b) {
        return Objects.equals(defaultVersionStatic(a), defaultVersionStatic(b));
    }

    private static String defaultVersionStatic(String version) {
        return StringUtils.hasText(version) ? version.trim() : "v1";
    }
}
