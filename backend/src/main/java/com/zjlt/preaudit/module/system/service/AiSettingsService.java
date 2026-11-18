package com.zjlt.preaudit.module.system.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 归因运行时开关。
 * <p>
 * 初值取自配置（preaudit.check.llm-attribution-enabled），运行时可通过设置接口切换，
 * 无需重启服务。仅控制核查时是否调用 LLM 为高风险疑点生成解释，不影响判定与金额。
 */
@Service
public class AiSettingsService {

    private final AtomicBoolean llmAttributionEnabled;
    private final AtomicBoolean reportAiEnabled;

    public AiSettingsService(
            @Value("${preaudit.check.llm-attribution-enabled:false}") boolean attributionInitial,
            @Value("${preaudit.report.ai-enabled:false}") boolean reportInitial) {
        this.llmAttributionEnabled = new AtomicBoolean(attributionInitial);
        this.reportAiEnabled = new AtomicBoolean(reportInitial);
    }

    public boolean isLlmAttributionEnabled() {
        return llmAttributionEnabled.get();
    }

    public void setLlmAttributionEnabled(boolean enabled) {
        llmAttributionEnabled.set(enabled);
    }

    public boolean isReportAiEnabled() {
        return reportAiEnabled.get();
    }

    public void setReportAiEnabled(boolean enabled) {
        reportAiEnabled.set(enabled);
    }
}
