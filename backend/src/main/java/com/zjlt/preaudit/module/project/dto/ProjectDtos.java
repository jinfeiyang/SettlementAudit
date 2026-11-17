package com.zjlt.preaudit.module.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目与资料 DTO
 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record SaveReq(String projectNo,
                          @NotBlank(message = "项目名称不能为空") String projectName,
                          String engType,
                          String contractNo,
                          String orderNo,
                          String builderUnit,
                          String supervisorUnit,
                          BigDecimal constructionFee,
                          String city,
                          BigDecimal settleAmount,
                          String remark) {
    }

    public record ProjectVO(Long id, String projectNo, String projectName, String engType, String engTypeName,
                            String contractNo, String orderNo, String builderUnit, String supervisorUnit,
                            BigDecimal constructionFee,
                            String city, BigDecimal settleAmount, String status, String statusName, String remark,
                            LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record FileVO(Long id, Long projectId, String fileType, String fileName, Long fileSize,
                         String parseStatus, String parseStatusName, BigDecimal parseRate,
                         String parseMessage, LocalDateTime createTime) {
    }

    /** 项目详情概览：主链路指标（解析率/命中率/疑点分级/报告状态） */
    public record Overview(Long projectId, String projectName, String status, String statusName,
                           int fileCount, int boqCount, int issueTotal,
                           int issueHigh, int issueMedium, int issueLow,
                           int issuePending, int issueConfirmed, int issueRejected,
                           BigDecimal settleAmount, BigDecimal boqTotal, BigDecimal checkAmount,
                           BigDecimal hitRate, BigDecimal feeDeviation,
                           Long reportId, String reportStatus, LocalDateTime lastCheckTime) {
    }
}
