package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 清单行与图纸材料的「手工匹配」结论（每个清单行最多一条，覆盖自动匹配结果）。
 *
 * MATCH：手工指定图纸材料；UNMATCH：手工取消匹配（不再自动关联任何图纸材料）。
 * 不记录时按自动匹配处理。
 */
@Getter
@Setter
@TableName("boq_drawing_match")
public class BoqDrawingMatch extends BaseEntity {

    private Long projectId;
    private Long boqItemId;
    /** 图纸材料键：名称|规格|单位（drawingCompare 里的 DrawingMaterial.key） */
    private String drawingKey;
    /** MATCH / UNMATCH */
    private String manualType;
}
