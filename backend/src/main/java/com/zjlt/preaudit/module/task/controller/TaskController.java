package com.zjlt.preaudit.module.task.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "异步任务")
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final AsyncTaskMapper asyncTaskMapper;

    @Operation(summary = "查询任务进度")
    @GetMapping("/{id}")
    public R<AsyncTask> get(@PathVariable Long id) {
        return R.ok(asyncTaskMapper.selectById(id));
    }
}
