package com.zjlt.preaudit.module.rule.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 定额子目规则（外置可配置，带版本与生效时间）
 */
@Getter
@Setter
@TableName("rule_quota")
public class RuleQuota extends BaseEntity {

    private String quotaCode;
    private String quotaName;
    private String unit;
    private BigDecimal basePrice;
    private BigDecimal laborPrice;
    /** 特征关键词 JSON 数组 */
    private String featureKeys;
    private String engType;
    private String version;
    private LocalDate effectiveDate;
    private Integer status;
}
