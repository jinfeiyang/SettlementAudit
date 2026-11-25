package com.zjlt.preaudit.module.report.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.report.dto.ReportDtos;
import com.zjlt.preaudit.module.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.util.List;

@Tag(name = "预审报告（C3/D4/D5）")
@RestController
@RequestMapping
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "生成报告初稿（模板装配 + AI 成文）")
    @PostMapping("/projects/{projectId}/report")
    public R<ReportDtos.ReportVO> generate(@PathVariable Long projectId) {
        return R.ok(reportService.generate(projectId));
    }

    @Operation(summary = "报告列表")
    @GetMapping("/reports")
    public R<List<ReportDtos.ReportVO>> list(@RequestParam(required = false) Long projectId) {
        return R.ok(reportService.list(projectId));
    }

    @Operation(summary = "报告详情")
    @GetMapping("/reports/{id}")
    public R<ReportDtos.ReportVO> get(@PathVariable Long id) {
        return R.ok(reportService.get(id));
    }

    @Operation(summary = "编辑报告（标题/结构化内容）")
    @PutMapping("/reports/{id}")
    public R<ReportDtos.ReportVO> update(@PathVariable Long id, @RequestBody ReportDtos.SaveReq req) {
        return R.ok(reportService.update(id, req));
    }

    @Operation(summary = "项目经理确认报告")
    @PostMapping("/reports/{id}/confirm")
    public R<ReportDtos.ReportVO> confirm(@PathVariable Long id) {
        return R.ok(reportService.confirm(id));
    }

    @Operation(summary = "删除报告（已确认的不可删）")
    @DeleteMapping("/reports/{id}")
    public R<Void> delete(@PathVariable Long id) {
        reportService.delete(id);
        return R.ok();
    }

    @Operation(summary = "导出 Word")
    @GetMapping("/reports/{id}/export")
    public void export(@PathVariable Long id, HttpServletResponse response) {
        reportService.exportWord(id, response);
    }

    @Operation(summary = "导出 PDF")
    @GetMapping("/reports/{id}/export/pdf")
    public void exportPdf(@PathVariable Long id, HttpServletResponse response) {
        reportService.exportPdf(id, response);
    }
}
