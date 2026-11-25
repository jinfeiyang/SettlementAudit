package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 施工光缆段与图纸页的人工关联。
 * 一段光缆可以关联多张图纸页，一页一条记录；frame 为 null 表示图框外的标注。
 */
@Getter
@Setter
@TableName("cable_drawing_match")
public class CableDrawingMatch extends BaseEntity {

    private Long projectId;
    /** 施工光缆段 id：boq_fixed_segment.id */
    private Long segmentId;
    /** 图纸文件 id */
    private Long fileId;
    /** 图框序号，null 表示图框外 */
    private Integer frame;
    /** 定位范围 JSON：minX/minY/maxX/maxY */
    private String bbox;
}
