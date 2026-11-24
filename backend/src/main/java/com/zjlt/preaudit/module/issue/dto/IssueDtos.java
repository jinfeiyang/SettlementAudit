package com.zjlt.preaudit.module.issue.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 疑点 DTO（D1/D3）
 */
public final class IssueDtos {

    private IssueDtos() {
    }

    public record IssueVO(Long id, Long projectId, String projectName, Long boqItemId, String issueType,
                          String issueTypeName, Integer level, String levelName, String ruleCode,
                          String refType, String refValue, String title, String detail,
                          String aiNote, String aiModel, String reviewStatus, String reviewStatusName,
                          LocalDateTime reviewTime, String reviewComment,
                          Integer rowNo, String itemCode, String itemName, String unit,
                          BigDecimal quantity, BigDecimal unitPrice, BigDecimal totalPrice,
                          String aiReferences) {
    }

    public record ReviewReq(@NotBlank(message = "复核动作不能为空") String action, String comment) {
    }

    public record IssueStats(int total, int high, int medium, int low,
                             int pending, int confirmed, int rejected) {
    }

    public record BatchReviewReq(java.util.List<Long> ids, @NotBlank String action, String comment) {
    }
}
