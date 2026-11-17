package com.zjlt.preaudit.module.check.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 核查内核 DTO（B1 定额三级匹配 / B2 取费核查）
 */
public final class CheckDtos {

    private CheckDtos() {
    }

    /** 定额候选（推荐前三名，供人工确认） */
    public record QuotaCandidate(String quotaCode, String quotaName, String unit, BigDecimal basePrice,
                                 BigDecimal score, String reason) {
    }

    /**
     * 匹配结果：level 1 精确 / 2 语义召回 / 3 未命中
     */
    public record MatchResult(int level, BigDecimal bestScore, List<QuotaCandidate> candidates,
                              boolean unitConvertible, String message) {
        public QuotaCandidate best() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    /** 单条取费明细 */
    public record FeeLine(String feeCode, String feeName, String baseFormula, BigDecimal rate,
                          BigDecimal base, BigDecimal amount) {
    }

    /** 取费核查结果；estimatedAmount 为按定额基价估算的直接费，coverage 为可核算行占比（%） */
    public record FeeResult(BigDecimal directCost, BigDecimal laborCost, BigDecimal feeTotal,
                            BigDecimal expectedAmount, BigDecimal settleAmount, BigDecimal deviation,
                            BigDecimal estimatedAmount, BigDecimal coverage, List<FeeLine> lines) {
    }

    /** 核查结果摘要（落 async_task.result_json，供工作台指标展示） */
    public record CheckSummary(int items, int issues, int issueHigh, int issueMedium, int issueLow,
                               BigDecimal hitRate, BigDecimal directCost, BigDecimal feeTotal,
                               BigDecimal checkAmount, BigDecimal settleAmount, BigDecimal feeDeviation,
                               long durationMs) {
    }
}
