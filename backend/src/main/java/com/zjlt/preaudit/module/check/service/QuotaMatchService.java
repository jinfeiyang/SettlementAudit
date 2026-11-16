package com.zjlt.preaudit.module.check.service;

import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * B1 定额套用三级核查：
 * 一级 精确匹配（编码/名称+单位）→ 二级 特征语义召回（关键词+名称相似度排序，取前三）→ 三级 换算系数校验
 */
@Service
public class QuotaMatchService {

    private static final int TOP_N = 3;

    private final double semanticThreshold;

    public QuotaMatchService(@Value("${preaudit.check.semantic-hit-threshold:0.55}") double semanticThreshold) {
        this.semanticThreshold = semanticThreshold;
    }

    public CheckDtos.MatchResult match(BoqItem item, List<RuleQuota> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return new CheckDtos.MatchResult(3, BigDecimal.ZERO, List.of(), false, "规则库中无可用定额子目");
        }
        String itemName = TextUtil.nullToEmpty(item.getItemName());
        String normalizedName = TextUtil.normalize(itemName);

        // 一级：编码完全一致
        if (item.getItemCode() != null && !item.getItemCode().isBlank()) {
            for (RuleQuota q : quotas) {
                if (TextUtil.normalize(item.getItemCode()).equals(TextUtil.normalize(q.getQuotaCode()))) {
                    return exact(q, "定额编号完全一致");
                }
            }
        }
        // 一级：名称+单位完全一致
        for (RuleQuota q : quotas) {
            if (TextUtil.normalize(q.getQuotaName()).equals(normalizedName)) {
                BigDecimal factor = TextUtil.unitFactor(item.getUnit(), q.getUnit());
                if (factor != null) {
                    return exact(q, "定额名称与单位一致");
                }
            }
        }

        // 二级：语义召回（名称相似度 + 特征关键词命中 + 编码前缀 + 单位/工程类型加成）
        List<CheckDtos.QuotaCandidate> ranked = new ArrayList<>();
        for (RuleQuota q : quotas) {
            double score = score(item, q);
            if (score <= 0.05) {
                continue;
            }
            ranked.add(new CheckDtos.QuotaCandidate(q.getQuotaCode(), q.getQuotaName(), q.getUnit(),
                    q.getBasePrice(), round(score), reason(item, q, score)));
        }
        ranked.sort(Comparator.comparing(CheckDtos.QuotaCandidate::score).reversed());
        List<CheckDtos.QuotaCandidate> top = ranked.size() > TOP_N ? ranked.subList(0, TOP_N) : ranked;
        if (top.isEmpty()) {
            return new CheckDtos.MatchResult(3, BigDecimal.ZERO, List.of(), false, "未召回任何定额候选");
        }
        double best = top.get(0).score().doubleValue();
        RuleQuota bestQuota = byCode(quotas, top.get(0).quotaCode());
        BigDecimal factor = bestQuota == null ? null : TextUtil.unitFactor(item.getUnit(), bestQuota.getUnit());
        boolean convertible = factor != null;
        if (best >= semanticThreshold) {
            String msg = convertible ? "语义召回命中，需人工确认" : "语义召回命中，但计量单位需确认换算系数";
            return new CheckDtos.MatchResult(2, round(best), top, convertible, msg);
        }
        return new CheckDtos.MatchResult(3, round(best), top, convertible, "候选置信度低于阈值，建议人工指定定额");
    }

    private CheckDtos.MatchResult exact(RuleQuota q, String reason) {
        CheckDtos.QuotaCandidate candidate = new CheckDtos.QuotaCandidate(q.getQuotaCode(), q.getQuotaName(),
                q.getUnit(), q.getBasePrice(), BigDecimal.ONE, reason);
        return new CheckDtos.MatchResult(1, BigDecimal.ONE, List.of(candidate), true, reason);
    }

    private double score(BoqItem item, RuleQuota q) {
        String itemName = TextUtil.nullToEmpty(item.getItemName());
        double nameSim = TextUtil.similarity(itemName, TextUtil.nullToEmpty(q.getQuotaName()));
        List<String> keys = JsonUtil.toStringList(q.getFeatureKeys());
        double keyHit = TextUtil.keywordHit(keys, itemName + " " + TextUtil.nullToEmpty(item.getFeatureDesc()));
        double codeBonus = 0;
        if (item.getItemCode() != null && !item.getItemCode().isBlank()) {
            String ic = TextUtil.normalize(item.getItemCode());
            String qc = TextUtil.normalize(q.getQuotaCode());
            if (ic.equals(qc)) {
                codeBonus = 0.30;
            } else if (ic.length() >= 3 && qc.length() >= 3 && ic.substring(0, 3).equals(qc.substring(0, 3))) {
                codeBonus = 0.06;
            }
        }
        double unitBonus = TextUtil.unitFactor(item.getUnit(), q.getUnit()) != null ? 0.08 : -0.10;
        double engBonus = 0;
        if (item.getEngType() != null && q.getEngType() != null) {
            engBonus = Objects.equals(item.getEngType(), q.getEngType()) ? 0.05 : -0.08;
        }
        double score = nameSim * 0.55 + keyHit * 0.35 + codeBonus + unitBonus + engBonus;
        return Math.max(0, Math.min(1, score));
    }

    private String reason(BoqItem item, RuleQuota q, double score) {
        List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.ROOT, "名称相似度 %.2f", TextUtil.similarity(
                TextUtil.nullToEmpty(item.getItemName()), TextUtil.nullToEmpty(q.getQuotaName()))));
        List<String> keys = JsonUtil.toStringList(q.getFeatureKeys());
        parts.add(String.format(Locale.ROOT, "特征命中 %.2f", TextUtil.keywordHit(keys,
                TextUtil.nullToEmpty(item.getItemName()) + " " + TextUtil.nullToEmpty(item.getFeatureDesc()))));
        if (TextUtil.unitFactor(item.getUnit(), q.getUnit()) == null) {
            parts.add("单位不一致");
        }
        parts.add(String.format(Locale.ROOT, "综合得分 %.2f", score));
        return String.join("，", parts);
    }

    private RuleQuota byCode(List<RuleQuota> quotas, String code) {
        return quotas.stream().filter(q -> Objects.equals(q.getQuotaCode(), code)).findFirst().orElse(null);
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
