package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工程量清单项（A2）：保留导入原文 + 标准化结果，row_no 用于疑点回溯定位（D2）
 */
@Getter
@Setter
@TableName("boq_item")
public class BoqItem extends BaseEntity {

    private Long projectId;
    private Long sourceFileId;
    /** 清单行号（回溯定位用） */
    private Integer rowNo;
    private String itemCode;
    private String itemName;
    private String featureDesc;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private String stdItemCode;
    private String stdItemName;
    private String engType;
    /** 推荐定额（B1 三级匹配结果，供前端展示与命中率统计） */
    private String matchQuotaCode;
    private String matchQuotaName;
    private java.math.BigDecimal matchScore;
    private Integer matchLevel;
}
