package com.zjlt.preaudit.module.check.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.service.ProjectService;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核查任务编排（G2 异步调度 + F1 状态流转）
 */
@Service
@RequiredArgsConstructor
public class CheckTaskService {

    private final ProjectService projectService;
    private final AsyncTaskMapper taskMapper;

    /** 创建核查任务：校验前置条件 + 置项目为核查中 */
    @Transactional
    public Long createTask(Long projectId) {
        Project project = projectService.requireProject(projectId);
        if (Dict.PROJ_CHECKING.equals(project.getStatus())) {
            throw new BizException("该项目正在核查中，请等待本次核查完成");
        }
        AsyncTask task = new AsyncTask();
        task.setProjectId(projectId);
        task.setTaskType("CHECK");
        task.setStatus(Dict.TASK_PENDING);
        task.setProgress(0);
        task.setTotal(0);
        task.setMessage("任务已创建，等待执行");
        taskMapper.insert(task);
        projectService.transit(projectId, Dict.PROJ_CHECKING);
        return task.getId();
    }

    /** 最近一次核查任务 */
    public AsyncTask lastTask(Long projectId) {
        return taskMapper.selectOne(Wrappers.<AsyncTask>lambdaQuery()
                .eq(AsyncTask::getProjectId, projectId)
                .eq(AsyncTask::getTaskType, "CHECK")
                .orderByDesc(AsyncTask::getId)
                .last("LIMIT 1"));
    }
}
