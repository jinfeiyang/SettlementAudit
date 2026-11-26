package com.zjlt.preaudit.module.boq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 人工结论统一台账：每条记录描述一次人工判断，而不是直接覆盖原始解析结果。
 * 撤销时按 type + ref 参数委托给原有业务方法，保证展示口径与旧逻辑一致。
 */
@Getter
@Setter
@TableName("manual_conclusion")
public class ManualConclusion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String type;
    private String bizKey;
    private String ref1;
    private String ref2;
    private String ref3;
    private String sourceDesc;
    private String summary;
    private String beforeValue;
    private String afterValue;
    private Long userId;
    private String username;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String revokeBy;
    private LocalDateTime revokeTime;
}
