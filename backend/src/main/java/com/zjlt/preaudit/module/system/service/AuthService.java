package com.zjlt.preaudit.module.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.PasswordUtil;
import com.zjlt.preaudit.common.security.TokenService;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.common.security.UserPrincipal;
import com.zjlt.preaudit.module.system.dto.AuthDtos;
import com.zjlt.preaudit.module.system.entity.SysUser;
import com.zjlt.preaudit.module.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 登录认证（RBAC 基线：AUDITOR 造价人员 / MANAGER 项目经理）
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final TokenService tokenService;

    public AuthDtos.LoginRes login(AuthDtos.LoginReq req) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, req.username().trim()));
        if (user == null) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(403, "账号已禁用");
        }
        if (!PasswordUtil.matches(user.getUsername(), req.password(), user.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(), user.getRealName(),
                user.getRoleCode());
        return new AuthDtos.LoginRes(tokenService.issue(principal), toVO(user));
    }

    public AuthDtos.UserVO me() {
        UserPrincipal current = UserContext.require();
        SysUser user = sysUserMapper.selectById(current.id());
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        return toVO(user);
    }

    private AuthDtos.UserVO toVO(SysUser user) {
        String roleName = Dict.ROLE_MANAGER.equals(user.getRoleCode()) ? "项目经理" : "造价人员";
        return new AuthDtos.UserVO(user.getId(), user.getUsername(), user.getRealName(),
                user.getRoleCode(), roleName);
    }
}
