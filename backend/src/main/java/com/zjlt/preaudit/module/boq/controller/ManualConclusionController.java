package com.zjlt.preaudit.module.boq.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.boq.entity.ManualConclusion;
import com.zjlt.preaudit.module.boq.service.ManualConclusionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "人工结论台账")
@RestController
@RequestMapping("/projects/{projectId}/manual-conclusions")
@RequiredArgsConstructor
public class ManualConclusionController {

    private final ManualConclusionService service;

    @Operation(summary = "查看项目下全部人工结论（含已撤销历史）")
    @GetMapping
    public R<List<ManualConclusion>> list(@PathVariable Long projectId) {
        return R.ok(service.list(projectId));
    }

    @Operation(summary = "撤销一条人工结论，恢复其原始解析值")
    @DeleteMapping("/{id}")
    public R<Void> revoke(@PathVariable Long projectId, @PathVariable Long id) {
        service.revoke(id);
        return R.ok();
    }
}
