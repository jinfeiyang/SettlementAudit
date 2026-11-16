package com.zjlt.preaudit.common.enums;

import java.util.List;
import java.util.Map;

/**
 * 一期字典常量与中文标签（落库值为英文码，展示/导出用标签）
 */
public final class Dict {

    private Dict() {
    }

    // 项目状态（F1 流程：导入→核查→复核→报告→归档）
    public static final String PROJ_DRAFT = "DRAFT";
    public static final String PROJ_IMPORTED = "IMPORTED";
    public static final String PROJ_CHECKING = "CHECKING";
    public static final String PROJ_CHECKED = "CHECKED";
    public static final String PROJ_REPORTED = "REPORTED";
    public static final String PROJ_ARCHIVED = "ARCHIVED";

    // 文件
    public static final String FILE_DWG = "DWG";
    public static final String FILE_DXF = "DXF";
    public static final String FILE_BOQ = "BOQ_EXCEL";
    public static final String FILE_RULE = "RULE_EXCEL";
    public static final String FILE_OTHER = "OTHER";

    public static final String PARSE_PENDING = "PENDING";
    public static final String PARSE_PARSING = "PARSING";
    public static final String PARSE_SUCCESS = "SUCCESS";
    public static final String PARSE_FAILED = "FAILED";

    // 异步任务
    public static final String TASK_PENDING = "PENDING";
    public static final String TASK_RUNNING = "RUNNING";
    public static final String TASK_SUCCESS = "SUCCESS";
    public static final String TASK_FAILED = "FAILED";

    // 疑点类型
    public static final String ISSUE_QUOTA = "QUOTA_MISMATCH";
    public static final String ISSUE_FEE = "FEE_ERROR";
    public static final String ISSUE_QTY = "QTY_ERROR";
    public static final String ISSUE_DUP = "DUP_CHARGE";
    public static final String ISSUE_OTHER = "OTHER";

    // 疑点级别
    public static final int LEVEL_HIGH = 3;
    public static final int LEVEL_MEDIUM = 2;
    public static final int LEVEL_LOW = 1;

    // 复核状态
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_REJECTED = "REJECTED";

    // 报告状态
    public static final String REPORT_DRAFT = "DRAFT";
    public static final String REPORT_CONFIRMED = "CONFIRMED";
    public static final String REPORT_EXPORTED = "EXPORTED";

    // 角色
    public static final String ROLE_AUDITOR = "AUDITOR";
    public static final String ROLE_MANAGER = "MANAGER";

    // 工程类型
    public static final String ENG_LINE = "LINE";
    public static final String ENG_DEVICE = "DEVICE";

    private static final Map<String, String> PROJECT_STATUS = Map.of(
            PROJ_DRAFT, "草稿",
            PROJ_IMPORTED, "已导入",
            PROJ_CHECKING, "核查中",
            PROJ_CHECKED, "已核查",
            PROJ_REPORTED, "已出报告",
            PROJ_ARCHIVED, "已归档");

    private static final Map<String, String> PARSE_STATUS = Map.of(
            PARSE_PENDING, "待解析",
            PARSE_PARSING, "解析中",
            PARSE_SUCCESS, "解析成功",
            PARSE_FAILED, "解析失败");

    private static final Map<String, String> ISSUE_TYPE = Map.of(
            ISSUE_QUOTA, "定额套用",
            ISSUE_FEE, "取费核查",
            ISSUE_QTY, "工程量/合价",
            ISSUE_DUP, "重复计费",
            ISSUE_OTHER, "其他");

    private static final Map<Integer, String> ISSUE_LEVEL = Map.of(
            LEVEL_HIGH, "高",
            LEVEL_MEDIUM, "中",
            LEVEL_LOW, "低");

    private static final Map<String, String> REVIEW_STATUS = Map.of(
            REVIEW_PENDING, "待复核",
            REVIEW_CONFIRMED, "已确认",
            REVIEW_REJECTED, "已驳回");

    private static final Map<String, String> ENG_TYPE = Map.of(
            ENG_LINE, "通信线路",
            ENG_DEVICE, "设备安装");

    /** 一期主链路状态流转（允许回退，便于重复核查） */
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            PROJ_DRAFT, List.of(PROJ_IMPORTED, PROJ_DRAFT),
            PROJ_IMPORTED, List.of(PROJ_CHECKING, PROJ_IMPORTED, PROJ_DRAFT),
            PROJ_CHECKING, List.of(PROJ_CHECKED, PROJ_CHECKING),
            PROJ_CHECKED, List.of(PROJ_CHECKING, PROJ_REPORTED, PROJ_CHECKED),
            PROJ_REPORTED, List.of(PROJ_ARCHIVED, PROJ_REPORTED, PROJ_CHECKING),
            PROJ_ARCHIVED, List.of(PROJ_ARCHIVED));

    public static String label(Map<String, String> dict, String key) {
        return key == null ? "" : dict.getOrDefault(key, key);
    }

    public static String projectStatus(String code) {
        return label(PROJECT_STATUS, code);
    }

    public static String parseStatus(String code) {
        return label(PARSE_STATUS, code);
    }

    public static String issueType(String code) {
        return label(ISSUE_TYPE, code);
    }

    public static String level(Integer level) {
        return level == null ? "" : ISSUE_LEVEL.getOrDefault(level, String.valueOf(level));
    }

    public static String reviewStatus(String code) {
        return label(REVIEW_STATUS, code);
    }

    public static String engType(String code) {
        return label(ENG_TYPE, code);
    }

    public static boolean canTransit(String from, String to) {
        return TRANSITIONS.getOrDefault(from, List.of()).contains(to);
    }
}
