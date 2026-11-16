package com.zjlt.preaudit.module.boq.controller;

import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.common.util.ExcelUtil;
import com.zjlt.preaudit.module.boq.dto.BoqDtos;
import com.zjlt.preaudit.module.boq.service.BoqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "工程量清单")
@RestController
@RequestMapping("/projects/{projectId}/boq")
@RequiredArgsConstructor
public class BoqController {

    private final BoqService boqService;

    @Operation(summary = "清单分页（含标准化与推荐定额）")
    @GetMapping
    public R<PageResult<BoqDtos.ItemVO>> page(@PathVariable Long projectId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "50") long size) {
        return R.ok(boqService.page(projectId, keyword, page, size));
    }

    @Operation(summary = "清单全量（前端导出/校验用）")
    @GetMapping("/all")
    public R<List<BoqDtos.ItemVO>> all(@PathVariable Long projectId) {
        return R.ok(boqService.listAll(projectId));
    }

    @Operation(summary = "按行号回溯定位（D2）")
    @GetMapping("/row/{rowNo}")
    public R<BoqDtos.ItemVO> byRow(@PathVariable Long projectId, @PathVariable Integer rowNo) {
        return R.ok(boqService.getByRow(projectId, rowNo));
    }

    @Operation(summary = "清空清单与疑点")
    @DeleteMapping
    public R<Void> clear(@PathVariable Long projectId) {
        boqService.clear(projectId);
        return R.ok();
    }

    @Operation(summary = "下载清单导入模板")
    @GetMapping("/template")
    public void template(HttpServletResponse response) {
        ExcelUtil.writeToResponse(response, BoqService.templateName(), BoqService.templateSheet(),
                BoqService.templateHeaders(), BoqService.templateRows());
    }
}
