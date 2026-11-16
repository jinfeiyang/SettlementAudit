package com.zjlt.preaudit.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 预审项目（A4）
 */
@Getter
@Setter
@TableName("proj_project")
public class Project extends BaseEntity {

    private String projectNo;
    private String projectName;
    /** LINE 线路 / DEVICE 设备安装 */
    private String engType;
    private String city;
    private Long ownerUserId;
    /** 结算送审金额 */
    private BigDecimal settleAmount;
    /** DRAFT/IMPORTED/CHECKING/CHECKED/REPORTED/ARCHIVED */
    private String status;
    private String remark;
}
