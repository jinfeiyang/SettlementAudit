package com.zjlt.preaudit.module.check.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.check.service.CheckRunner;
import com.zjlt.preaudit.module.check.service.CheckTaskService;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "自动核查（B1 定额 / B2 取费）")
@RestController
@RequestMapping("/projects/{projectId}/check")
@RequiredArgsConstructor
public class CheckController {

    private final CheckTaskService checkTaskService;
    private final CheckRunner checkRunner;

    @Operation(summary = "发起核查（异步，返回 taskId 供轮询）")
    @PostMapping
    public R<Map<String, Object>> start(@PathVariable Long projectId) {
        Long taskId = checkTaskService.createTask(projectId);
        checkRunner.runAsync(taskId);
        return R.ok(Map.of("taskId", taskId));
    }

    @Operation(summary = "同步核查（小数据量/联调自测用）")
    @PostMapping("/sync")
    public R<AsyncTask> runSync(@PathVariable Long projectId) {
        Long taskId = checkTaskService.createTask(projectId);
        checkRunner.execute(taskId);
        return R.ok(checkTaskService.lastTask(projectId));
    }

    @Operation(summary = "最近一次核查任务与结果摘要")
    @GetMapping("/last")
    public R<AsyncTask> last(@PathVariable Long projectId) {
        return R.ok(checkTaskService.lastTask(projectId));
    }
}
