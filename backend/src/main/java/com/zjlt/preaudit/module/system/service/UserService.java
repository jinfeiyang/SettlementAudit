package com.zjlt.preaudit.module.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.PasswordUtil;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.module.system.dto.UserDtos;
import com.zjlt.preaudit.module.system.entity.SysUser;
import com.zjlt.preaudit.module.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 系统用户管理：项目经理维护造价人员账号、角色、启停与密码。
 *
 * 本地账号是一期 RBAC 基线（接入 4A 后由平台托管），所以只提供最小必要操作，
 * 且不允许把自己停用/删除，也不允许把最后一个启用状态的项目经理停掉。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Set<String> ROLES = Set.of(Dict.ROLE_AUDITOR, Dict.ROLE_MANAGER);
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final SysUserMapper sysUserMapper;

    public PageResult<UserDtos.UserVO> page(long page, long size, String keyword) {
        requireManager();
        var query = Wrappers.<SysUser>lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String key = keyword.trim();
            query.and(w -> w.like(SysUser::getUsername, key).or().like(SysUser::getRealName, key));
        }
        query.orderByAsc(SysUser::getId);
        IPage<SysUser> result = sysUserMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result, this::toVO);
    }

    @Transactional
    public UserDtos.UserVO create(UserDtos.CreateReq req) {
        requireManager();
        if (req == null || !StringUtils.hasText(req.username())) {
            throw new BizException("用户名不能为空");
        }
        String username = req.username().trim();
        if (!username.matches("[A-Za-z0-9_.-]{3,32}")) {
            throw new BizException("用户名需为 3~32 位字母、数字或 _.-");
        }
        checkPassword(req.password());
        checkRole(req.roleCode());
        if (sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)) > 0) {
            throw new BizException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordUtil.hash(username, req.password()));
        user.setRealName(trimToNull(req.realName()));
        user.setRoleCode(req.roleCode());
        user.setStatus(1);
        sysUserMapper.insert(user);
        return toVO(user);
    }

    @Transactional
    public UserDtos.UserVO update(Long id, UserDtos.UpdateReq req) {
        requireManager();
        SysUser user = requireUser(id);
        if (req == null) {
            return toVO(user);
        }
        if (req.roleCode() != null) {
            checkRole(req.roleCode());
        }
        if (req.status() != null && req.status() != 0 && req.status() != 1) {
            throw new BizException("账号状态只能是启用或禁用");
        }
        if (id.equals(UserContext.userId())
                && ((req.status() != null && req.status() == 0)
                || (req.roleCode() != null && !Dict.ROLE_MANAGER.equals(req.roleCode())))) {
            throw new BizException("不能停用或降级当前登录账号");
        }
        boolean stillManager = Dict.ROLE_MANAGER.equals(
                req.roleCode() == null ? user.getRoleCode() : req.roleCode());
        boolean enabled = req.status() == null ? user.getStatus() != null && user.getStatus() == 1
                : req.status() == 1;
        if (Dict.ROLE_MANAGER.equals(user.getRoleCode()) && (!stillManager || !enabled)) {
            checkLastManager(id);
        }
        if (req.realName() != null) {
            user.setRealName(trimToNull(req.realName()));
        }
        if (req.roleCode() != null) {
            user.setRoleCode(req.roleCode());
        }
        if (req.status() != null) {
            user.setStatus(req.status());
        }
        sysUserMapper.updateById(user);
        return toVO(user);
    }

    @Transactional
    public void resetPassword(Long id, UserDtos.PasswordReq req) {
        requireManager();
        SysUser user = requireUser(id);
        checkPassword(req == null ? null : req.password());
        user.setPassword(PasswordUtil.hash(user.getUsername(), req.password()));
        sysUserMapper.updateById(user);
    }

    @Transactional
    public void delete(Long id) {
        requireManager();
        SysUser user = requireUser(id);
        if (id.equals(UserContext.userId())) {
            throw new BizException("不能删除当前登录账号");
        }
        if (Dict.ROLE_MANAGER.equals(user.getRoleCode()) && user.getStatus() != null && user.getStatus() == 1) {
            checkLastManager(id);
        }
        sysUserMapper.deleteById(id);
    }

    /** 用户列表查询条件为空时 MyBatis-Plus 的 or() 不能把其它条件带空，这里单独兜底 */
    private void requireManager() {
        if (!Dict.ROLE_MANAGER.equals(UserContext.roleCode())) {
            throw new BizException(403, "仅项目经理可管理用户");
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = id == null ? null : sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    private void checkLastManager(Long id) {
        Long managers = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRoleCode, Dict.ROLE_MANAGER)
                .eq(SysUser::getStatus, 1)
                .ne(SysUser::getId, id));
        if (managers == null || managers == 0) {
            throw new BizException("至少需要保留一个启用状态的项目经理账号");
        }
    }

    private void checkRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new BizException("角色只能是造价人员或项目经理");
        }
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException("密码至少 6 位");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private UserDtos.UserVO toVO(SysUser user) {
        boolean enabled = user.getStatus() == null || user.getStatus() == 1;
        String roleName = Dict.ROLE_MANAGER.equals(user.getRoleCode()) ? "项目经理" : "造价人员";
        return new UserDtos.UserVO(user.getId(), user.getUsername(), user.getRealName(),
                user.getRoleCode(), roleName, enabled ? 1 : 0, enabled ? "启用" : "禁用", user.getCreateTime());
    }
}
