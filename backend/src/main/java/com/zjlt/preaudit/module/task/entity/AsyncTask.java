package com.zjlt.preaudit.module.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zjlt.preaudit.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 异步任务（G2）：核查/解析任务进度与结果摘要
 */
@Getter
@Setter
@TableName("async_task")
public class AsyncTask extends BaseEntity {

    private Long projectId;
    /** CHECK / PARSE */
    private String taskType;
    /** PENDING/RUNNING/SUCCESS/FAILED */
    private String status;
    private Integer progress;
    private Integer total;
    private String message;
    private String resultJson;
}
