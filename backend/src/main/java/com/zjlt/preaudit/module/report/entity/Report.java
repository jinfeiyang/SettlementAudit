package com.zjlt.preaudit.module.report.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 预审报告（C3/D4）：结构化内容 JSON，支持编辑、确认与 Word 导出
 */
@Getter
@Setter
@TableName("report")
public class Report extends BaseEntity {

    private Long projectId;
    private String title;
    /** 结构化内容 JSON：{ sections: [{title,type,text|columns|rows}] } */
    private String content;
    private String aiModel;
    /** DRAFT/CONFIRMED/EXPORTED */
    private String status;
    private Integer version;
    private Long confirmUserId;
    private LocalDateTime confirmTime;
}
