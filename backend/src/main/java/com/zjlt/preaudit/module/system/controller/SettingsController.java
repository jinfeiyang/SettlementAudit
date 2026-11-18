package com.zjlt.preaudit.module.system.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.system.service.AiSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 功能运行时设置 */
@Tag(name = "系统设置")
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AiSettingsService aiSettingsService;

    @Operation(summary = "查询 AI 设置")
    @GetMapping
    public R<SettingsVO> get() {
        SettingsVO vo = new SettingsVO();
        vo.setLlmAttributionEnabled(aiSettingsService.isLlmAttributionEnabled());
        vo.setReportAiEnabled(aiSettingsService.isReportAiEnabled());
        return R.ok(vo);
    }

    @Operation(summary = "更新 AI 设置")
    @PutMapping
    public R<SettingsVO> update(@RequestBody SettingsVO body) {
        aiSettingsService.setLlmAttributionEnabled(body.isLlmAttributionEnabled());
        aiSettingsService.setReportAiEnabled(body.isReportAiEnabled());
        return get();
    }

    @Data
    public static class SettingsVO {
        private boolean llmAttributionEnabled;
        private boolean reportAiEnabled;
    }
}
