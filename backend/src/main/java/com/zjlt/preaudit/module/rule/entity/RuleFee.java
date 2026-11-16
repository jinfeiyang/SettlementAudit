package com.zjlt.preaudit.module.rule.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 取费规则（取费基数公式 + 费率，按工程类别/类型与版本生效）
 */
@Getter
@Setter
@TableName("rule_fee")
public class RuleFee extends BaseEntity {

    private String feeCode;
    private String feeName;
    private String baseFormula;
    private BigDecimal rate;
    private String engCategory;
    private String engType;
    private String version;
    private LocalDate effectiveDate;
    private Integer status;
}
