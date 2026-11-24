package com.zjlt.preaudit.module.issue.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 疑点（D1）：三级分级 + 来源追溯（规则/清单行号）+ AI 归因 + 人工复核
 */
@Getter
@Setter
@TableName("issue")
public class Issue extends BaseEntity {

    private Long projectId;
    private Long boqItemId;
    /** QUOTA_MISMATCH/FEE_ERROR/QTY_ERROR/DUP_CHARGE/OTHER */
    private String issueType;
    /** 3 高 / 2 中 / 1 低 */
    private Integer level;
    private Long ruleId;
    /** 命中规则标识（B1-EXACT / B1-SEMANTIC / B2-FEE-DEVIATION …） */
    private String ruleCode;
    /** BOQ_ROW 清单行号 / ENTITY_ID 图元 */
    private String refType;
    private String refValue;
    private String title;
    private String detail;
    /** LLM 归因解释（仅建议，不写金额、不下结论） */
    private String aiNote;
    private String aiModel;
    /** 归因依据（JSON 数组）：清单行/定额子目/取费规则/判定口径，确定性内核给出，不依赖模型 */
    private String aiReferences;
    /** PENDING/CONFIRMED/REJECTED */
    private String reviewStatus;
    private Long reviewerId;
    private LocalDateTime reviewTime;
    private String reviewComment;
}
