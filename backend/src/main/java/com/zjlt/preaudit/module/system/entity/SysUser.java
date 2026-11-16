package com.zjlt.preaudit.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统用户（RBAC 基线）
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    private String password;
    private String realName;
    private String roleCode;
    private Integer status;
}
