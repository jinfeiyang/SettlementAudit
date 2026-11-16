package com.zjlt.preaudit.module.rule.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 规则库 DTO（A3）
 */
public final class RuleDtos {

    private RuleDtos() {
    }

    public record QuotaReq(@NotBlank(message = "定额编号不能为空") String quotaCode,
                           @NotBlank(message = "定额名称不能为空") String quotaName,
                           String unit, BigDecimal basePrice, BigDecimal laborPrice,
                           List<String> featureKeys, String engType, String version,
                           LocalDate effectiveDate, Integer status) {
    }

    public record QuotaVO(Long id, String quotaCode, String quotaName, String unit, BigDecimal basePrice,
                          BigDecimal laborPrice, List<String> featureKeys, String engType, String engTypeName,
                          String version, LocalDate effectiveDate, Integer status) {
    }

    public record FeeReq(@NotBlank(message = "费用项编码不能为空") String feeCode,
                         @NotBlank(message = "费用项名称不能为空") String feeName,
                         String baseFormula, BigDecimal rate, String engCategory, String engType,
                         String version, LocalDate effectiveDate, Integer status) {
    }

    public record FeeVO(Long id, String feeCode, String feeName, String baseFormula, BigDecimal rate,
                        String engCategory, String engType, String version, LocalDate effectiveDate,
                        Integer status) {
    }

    public record ImportResult(int rows, int imported, int skipped, String message) {
    }
}
