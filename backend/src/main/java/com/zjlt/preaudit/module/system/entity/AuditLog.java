package com.zjlt.preaudit.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计日志（G3）：记录写操作的用户、路径、参数摘要与来源 IP
 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String target;
    private String detail;
    private String ip;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
