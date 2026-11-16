package com.zjlt.preaudit.controller;

import com.zjlt.preaudit.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Tag(name = "系统健康")
@RestController
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "service", "preaudit-backend",
                "version", "0.1.0",
                "time", LocalDateTime.now().toString()
        ));
    }
}
