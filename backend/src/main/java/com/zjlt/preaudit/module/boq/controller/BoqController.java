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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "表七光缆施工基准价逐段明细（含重复段标记）")
    @GetMapping("/fixed-segments")
    public R<List<BoqDtos.FixedSegmentVO>> fixedSegments(@PathVariable Long projectId) {
        return R.ok(boqService.listFixedSegments(projectId));
    }

    @Operation(summary = "表七光缆段的人工图纸关联（全项目）")
    @GetMapping("/cable-drawing-matches")
    public R<List<BoqDtos.CableDrawingMatchVO>> cableDrawingMatches(@PathVariable Long projectId) {
        return R.ok(boqService.listCableDrawingMatches(projectId));
    }

    @Operation(summary = "保存表七光缆段的图纸关联（整段覆盖，空列表清空）")
    @PostMapping("/cable-drawing-matches")
    public R<Void> saveCableDrawingMatches(@PathVariable Long projectId,
                                           @RequestBody BoqDtos.CableDrawingMatchReq req) {
        boqService.saveCableDrawingMatches(projectId, req);
        return R.ok();
    }

    @Operation(summary = "清单行的人工图纸匹配结论")
    @GetMapping("/drawing-matches")
    public R<List<BoqDtos.MatchOverrideVO>> drawingMatches(@PathVariable Long projectId) {
        return R.ok(boqService.listDrawingMatches(projectId));
    }

    @Operation(summary = "保存人工匹配（不传 drawingKey 表示取消匹配）")
    @PostMapping("/drawing-matches")
    public R<Void> saveDrawingMatch(@PathVariable Long projectId,
                                    @RequestBody BoqDtos.MatchOverrideReq req) {
        boqService.saveDrawingMatch(projectId, req);
        return R.ok();
    }

    @Operation(summary = "恢复该清单行的自动匹配")
    @DeleteMapping("/drawing-matches/{boqItemId}")
    public R<Void> deleteDrawingMatch(@PathVariable Long projectId, @PathVariable Long boqItemId) {
        boqService.deleteDrawingMatch(projectId, boqItemId);
        return R.ok();
    }

    @Operation(summary = "图纸材料的人工合并结论")
    @GetMapping("/drawing-merges")
    public R<List<BoqDtos.MaterialMergeVO>> drawingMerges(@PathVariable Long projectId) {
        return R.ok(boqService.listDrawingMerges(projectId));
    }

    @Operation(summary = "保存图纸材料合并组（同物异名合并为一行）")
    @PostMapping("/drawing-merges")
    public R<Void> saveDrawingMerges(@PathVariable Long projectId,
                                     @RequestBody BoqDtos.MaterialMergeReq req) {
        boqService.saveDrawingMerges(projectId, req);
        return R.ok();
    }

    @Operation(summary = "拆分图纸材料合并组")
    @DeleteMapping("/drawing-merges/group/{groupKey}")
    public R<Void> deleteDrawingMerges(@PathVariable Long projectId, @PathVariable String groupKey) {
        boqService.deleteDrawingMerges(projectId, groupKey);
        return R.ok();
    }

    @Operation(summary = "编辑清单行（数量/单价变化后合价自动重算）")
    @PutMapping("/items/{itemId}")
    public R<Void> updateItem(@PathVariable Long projectId,
                              @PathVariable Long itemId,
                              @RequestBody BoqDtos.ItemEditReq req) {
        boqService.updateItem(projectId, itemId, req);
        return R.ok();
    }

    @Operation(summary = "图纸材料的人工编辑结论")
    @GetMapping("/drawing-edits")
    public R<List<BoqDtos.MaterialEditVO>> drawingEdits(@PathVariable Long projectId) {
        return R.ok(boqService.listDrawingEdits(projectId));
    }

    @Operation(summary = "保存图纸材料编辑（名称/规格/单位/数量，材料关联不变）")
    @PostMapping("/drawing-edits")
    public R<Void> saveDrawingEdit(@PathVariable Long projectId,
                                   @RequestBody BoqDtos.MaterialEditReq req) {
        boqService.saveDrawingEdit(projectId, req);
        return R.ok();
    }

    @Operation(summary = "恢复图纸材料的原始解析结果")
    @DeleteMapping("/drawing-edits/{materialKey}")
    public R<Void> deleteDrawingEdit(@PathVariable Long projectId, @PathVariable String materialKey) {
        boqService.deleteDrawingEdit(projectId, materialKey);
        return R.ok();
    }

    @Operation(summary = "清空清单与疑点")
    @DeleteMapping
    public R<Void> clear(@PathVariable Long projectId) {
        boqService.clear(projectId);
        return R.ok();
    }

    @Operation(summary = "下载清单导入模板（联通决算全套表，已清空数据行）")
    @GetMapping("/template")
    public void template(HttpServletResponse response) {
        ExcelUtil.downloadResource(response, "templates/工程量清单导入模板.xls", "工程量清单导入模板.xls");
    }
}
