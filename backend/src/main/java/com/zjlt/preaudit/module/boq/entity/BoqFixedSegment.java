package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 表七光缆施工基准价逐段明细（B4 重复计费识别数据源）。
 * 表七原本只按芯数聚合当计价依据，段落名用完即弃；
 * 段落反转/近似重复是重复计费的主要形态，故逐段落库并保留去重键与分组结果。
 */
@Getter
@Setter
@TableName("boq_fixed_segment")
public class BoqFixedSegment extends BaseEntity {

    private Long projectId;
    private Long sourceFileId;
    /** 表七原始行号 */
    private Integer rowNo;
    private String segmentName;
    private Integer coreCount;
    private BigDecimal length;
    private BigDecimal feeRate;
    private BigDecimal discount;
    private BigDecimal otherFee;
    private BigDecimal fuseCores;
    private BigDecimal fuseRate;
    private BigDecimal amount;
    /** 段落名规范化 + 起止点排序后的键，反转段落同键 */
    private String dedupKey;
    /** 0 无重复；>0 同组为疑似重复 */
    private Integer dupGroup;
    /** REVERSED 起止点反转 / SIMILAR 名称高度相似 */
    private String dupType;
}
