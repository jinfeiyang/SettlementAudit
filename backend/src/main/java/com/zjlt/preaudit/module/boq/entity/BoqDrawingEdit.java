package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 图纸材料的人工编辑：图纸清单里的名称/规格/单位/数量按材料键覆盖，
 * 材料键保持不变，已有的自动匹配、人工匹配与合并组不会因为改名而断开关联。
 */
@Getter
@Setter
@TableName("drawing_material_edit")
public class BoqDrawingEdit extends BaseEntity {

    private Long projectId;
    /** 被编辑的材料键：名称|规格|单位（drawingCompare 里的 DrawingMaterial.key） */
    private String materialKey;
    private String name;
    private String spec;
    private String unit;
    private BigDecimal quantity;
}
