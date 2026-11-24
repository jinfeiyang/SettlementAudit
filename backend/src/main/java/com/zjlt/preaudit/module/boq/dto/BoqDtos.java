package com.zjlt.preaudit.module.boq.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 清单 DTO
 */
public final class BoqDtos {

    private BoqDtos() {
    }

    public record ItemVO(Long id, Long projectId, Integer rowNo, String itemType, String itemTypeName,
                         String sourceSheet, String itemCode, String itemName,
                         String featureDesc, String unit, BigDecimal quantity, BigDecimal unitPrice,
                         BigDecimal totalPrice, String stdItemCode, String stdItemName, String engType,
                         String matchQuotaCode, String matchQuotaName, BigDecimal matchScore,
                         Integer matchLevel) {
    }

    /** 清单行与图纸材料的人工匹配结论：MATCH 指定材料，UNMATCH 取消匹配 */
    public record MatchOverrideVO(Long boqItemId, String drawingKey, String manualType) {
    }

    /** 保存人工匹配：manualType 为 UNMATCH 时取消匹配，否则按 drawingKey 手工指定材料 */
    public record MatchOverrideReq(Long boqItemId, String drawingKey, String manualType) {
    }

    /** 图纸材料人工合并：一个成员一条记录，groupKey 相同表示同一合并组 */
    public record MaterialMergeVO(String memberKey, String groupKey, String displayName) {
    }

    /** 保存合并组：同一批成员里已有其它合并结论的会先被撤掉，保证一个成员只在一个组 */
    public record MaterialMergeReq(String groupKey, String displayName, List<String> memberKeys) {
    }

    /** 编辑清单行：只允许改名称/规格/单位/数量/单价，合价按数量 × 单价重算 */
    public record ItemEditReq(String itemName, String featureDesc, String unit,
                              BigDecimal quantity, BigDecimal unitPrice) {
    }

    /** 图纸材料人工编辑：材料键不变，只覆盖展示字段与数量 */
    public record MaterialEditVO(String materialKey, String name, String spec,
                                 String unit, BigDecimal quantity) {
    }

    public record MaterialEditReq(String materialKey, String name, String spec,
                                  String unit, BigDecimal quantity) {
    }

    /** 导入结果：行数、可解析率、识别工程类型 */
    public record ImportResult(Long fileId, int rows, int imported, BigDecimal parseRate,
                               String engType, String engTypeName, String message) {
    }

    /** 表七逐段明细（B4 重复计费识别数据源） */
    public record FixedSegmentVO(Long id, Integer rowNo, String segmentName, Integer coreCount,
                                 BigDecimal length, BigDecimal amount, String dedupKey,
                                 Integer dupGroup, String dupType) {
    }
}
