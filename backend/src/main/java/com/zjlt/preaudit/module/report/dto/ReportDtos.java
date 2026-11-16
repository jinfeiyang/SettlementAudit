package com.zjlt.preaudit.module.report.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预审报告 DTO（C3 初稿生成 / D4 管理 / D5 编辑）
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record ReportVO(Long id, Long projectId, String projectName, String title, String content,
                           String aiModel, String status, String statusName, Integer version,
                           LocalDateTime confirmTime, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record SaveReq(String title, String content) {
    }

    /** 报告结构化内容（Word 导出与前端编辑共用） */
    public record ReportContent(List<Section> sections) {
    }

    public record Section(String title, String type, String text,
                          List<String> columns, List<List<String>> rows) {
        public static Section text(String title, String text) {
            return new Section(title, "text", text, null, null);
        }

        public static Section table(String title, List<String> columns, List<List<String>> rows) {
            return new Section(title, "table", null, columns, rows);
        }
    }
}
