package com.zjlt.preaudit.module.project.controller;

import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.project.dto.ProjectDtos;
import com.zjlt.preaudit.module.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "预审项目")
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "项目分页")
    @GetMapping
    public R<PageResult<ProjectDtos.ProjectVO>> page(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String engType,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "10") long size) {
        return R.ok(projectService.page(keyword, status, engType, page, size));
    }

    @Operation(summary = "新建项目")
    @PostMapping
    public R<ProjectDtos.ProjectVO> create(@Valid @RequestBody ProjectDtos.SaveReq req) {
        return R.ok(projectService.create(req));
    }

    @Operation(summary = "修改项目")
    @PutMapping("/{id}")
    public R<ProjectDtos.ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectDtos.SaveReq req) {
        return R.ok(projectService.update(id, req));
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return R.ok();
    }

    @Operation(summary = "项目详情")
    @GetMapping("/{id}")
    public R<ProjectDtos.ProjectVO> get(@PathVariable Long id) {
        return R.ok(projectService.get(id));
    }

    @Operation(summary = "项目概览指标（D1 疑点分级/命中率/报告状态）")
    @GetMapping("/{id}/overview")
    public R<ProjectDtos.Overview> overview(@PathVariable Long id) {
        return R.ok(projectService.overview(id));
    }

    @Operation(summary = "状态流转（F1）")
    @PostMapping("/{id}/status")
    public R<Void> transit(@PathVariable Long id, @RequestParam String target) {
        projectService.transit(id, target);
        return R.ok();
    }
}
