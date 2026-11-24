package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 图纸材料的人工合并：把同物异名的多条材料（如「安装ODF」「新装ODF」「安装ODF子框」）
 * 归为一个合并组，图纸清单里合成一行、数量相加后再参与清单匹配。
 *
 * 一个合并组有多条记录，group_key 相同，每条记录对应一个成员材料键。
 */
@Getter
@Setter
@TableName("drawing_material_merge")
public class BoqDrawingMerge extends BaseEntity {

    private Long projectId;
    /** 合并组标识，前端生成（merge:UUID） */
    private String groupKey;
    /** 合并后展示的名称，为空时沿用组内第一条的名称 */
    private String displayName;
    /** 成员材料键：名称|规格|单位（drawingCompare 里的 DrawingMaterial.key） */
    private String memberKey;
}
