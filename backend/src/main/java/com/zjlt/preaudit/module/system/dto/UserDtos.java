package com.zjlt.preaudit.module.system.dto;

import java.time.LocalDateTime;

/** 系统用户管理接口的参数与响应 */
public class UserDtos {

    public record UserVO(Long id, String username, String realName, String roleCode, String roleName,
                         Integer status, String statusName, LocalDateTime createTime) {
    }

    public record CreateReq(String username, String password, String realName, String roleCode) {
    }

    public record UpdateReq(String realName, String roleCode, Integer status) {
    }

    public record PasswordReq(String password) {
    }
}
