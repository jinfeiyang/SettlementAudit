package com.zjlt.preaudit.module.boq.dto;

import java.math.BigDecimal;

/**
 * 清单 DTO
 */
public final class BoqDtos {

    private BoqDtos() {
    }

    public record ItemVO(Long id, Long projectId, Integer rowNo, String itemCode, String itemName,
                         String featureDesc, String unit, BigDecimal quantity, BigDecimal unitPrice,
                         BigDecimal totalPrice, String stdItemCode, String stdItemName, String engType,
                         String matchQuotaCode, String matchQuotaName, BigDecimal matchScore,
                         Integer matchLevel) {
    }

    /** 导入结果：行数、可解析率、识别工程类型 */
    public record ImportResult(Long fileId, int rows, int imported, BigDecimal parseRate,
                               String engType, String engTypeName, String message) {
    }
}
