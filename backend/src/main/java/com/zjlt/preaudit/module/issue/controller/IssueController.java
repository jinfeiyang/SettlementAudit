package com.zjlt.preaudit.module.issue.controller;

import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.issue.dto.IssueDtos;
import com.zjlt.preaudit.module.issue.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "疑点清单与复核（D1/D2/D3）")
@RestController
@RequestMapping("/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @Operation(summary = "疑点分页（三级分级 + 类型/复核状态筛选）")
    @GetMapping
    public R<PageResult<IssueDtos.IssueVO>> page(@RequestParam(required = false) Long projectId,
                                                 @RequestParam(required = false) Integer level,
                                                 @RequestParam(required = false) String issueType,
                                                 @RequestParam(required = false) String reviewStatus,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        return R.ok(issueService.page(projectId, level, issueType, reviewStatus, keyword, page, size));
    }

    @Operation(summary = "疑点统计")
    @GetMapping("/stats")
    public R<IssueDtos.IssueStats> stats(@RequestParam(required = false) Long projectId) {
        return R.ok(issueService.stats(projectId));
    }

    @Operation(summary = "疑点详情")
    @GetMapping("/{id}")
    public R<IssueDtos.IssueVO> get(@PathVariable Long id) {
        return R.ok(issueService.get(id));
    }

    @Operation(summary = "一键回溯定位（清单行号）")
    @GetMapping("/{id}/locate")
    public R<IssueDtos.IssueVO> locate(@PathVariable Long id) {
        return R.ok(issueService.locate(id));
    }

    @Operation(summary = "复核（确认/驳回）")
    @PostMapping("/{id}/review")
    public R<Void> review(@PathVariable Long id, @Valid @RequestBody IssueDtos.ReviewReq req) {
        issueService.review(id, req);
        return R.ok();
    }

    @Operation(summary = "批量复核")
    @PostMapping("/batch-review")
    public R<Integer> batchReview(@Valid @RequestBody IssueDtos.BatchReviewReq req) {
        return R.ok(issueService.batchReview(req));
    }

    @Operation(summary = "导出疑点清单 Excel")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long projectId,
                       @RequestParam(required = false) Integer level,
                       @RequestParam(required = false) String issueType,
                       @RequestParam(required = false) String reviewStatus,
                       HttpServletResponse response) {
        issueService.export(projectId, level, issueType, reviewStatus, response);
    }
}
