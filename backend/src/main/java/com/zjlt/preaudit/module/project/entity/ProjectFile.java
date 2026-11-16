package com.zjlt.preaudit.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 项目资料文件（图纸/清单/结算书）
 */
@Getter
@Setter
@TableName("proj_file")
public class ProjectFile extends BaseEntity {

    private Long projectId;
    /** DWG/DXF/BOQ_EXCEL/RULE_EXCEL/SETTLE_DOC/OTHER */
    private String fileType;
    private String fileName;
    /** 相对存储路径 */
    private String storagePath;
    private Long fileSize;
    /** PENDING/PARSING/SUCCESS/FAILED */
    private String parseStatus;
    /** 清单可解析率（%） */
    private BigDecimal parseRate;
    private String parseMessage;
    /** 解析结果 JSON（图元量算/清单统计） */
    private String parseResult;
}
