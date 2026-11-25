package com.zjlt.preaudit.module.system.controller;

import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.system.dto.UserDtos;
import com.zjlt.preaudit.module.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统用户管理（仅项目经理） */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户分页列表")
    @GetMapping
    public R<PageResult<UserDtos.UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String keyword) {
        return R.ok(userService.page(page, size, keyword));
    }

    @Operation(summary = "新增用户")
    @PostMapping
    public R<UserDtos.UserVO> create(@RequestBody UserDtos.CreateReq req) {
        return R.ok(userService.create(req));
    }

    @Operation(summary = "编辑用户（姓名/角色/状态）")
    @PutMapping("/{id}")
    public R<UserDtos.UserVO> update(@PathVariable Long id, @RequestBody UserDtos.UpdateReq req) {
        return R.ok(userService.update(id, req));
    }

    @Operation(summary = "重置密码")
    @PutMapping("/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody UserDtos.PasswordReq req) {
        userService.resetPassword(id, req);
        return R.ok();
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }
}
