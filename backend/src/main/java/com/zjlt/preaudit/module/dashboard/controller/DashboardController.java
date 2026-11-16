package com.zjlt.preaudit.module.dashboard.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.entity.ProjectFile;
import com.zjlt.preaudit.module.project.mapper.ProjectFileMapper;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import com.zjlt.preaudit.common.util.JsonUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "工作台指标")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ProjectMapper projectMapper;
    private final ProjectFileMapper projectFileMapper;
    private final IssueMapper issueMapper;
    private final AsyncTaskMapper taskMapper;

    @Operation(summary = "工作台统计（项目/疑点/命中率/解析率）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        long projectTotal = count(projectMapper.selectCount(null));
        long checking = count(projectMapper.selectCount(Wrappers.<Project>lambdaQuery()
                .eq(Project::getStatus, Dict.PROJ_CHECKING)));
        long checked = count(projectMapper.selectCount(Wrappers.<Project>lambdaQuery()
                .eq(Project::getStatus, Dict.PROJ_CHECKED)));
        long reported = count(projectMapper.selectCount(Wrappers.<Project>lambdaQuery()
                .eq(Project::getStatus, Dict.PROJ_REPORTED)));

        List<Issue> issues = issueMapper.selectList(Wrappers.<Issue>lambdaQuery());
        int high = 0;
        int medium = 0;
        int low = 0;
        int pending = 0;
        Map<String, Integer> typeDist = new LinkedHashMap<>();
        for (Issue issue : issues) {
            int level = issue.getLevel() == null ? 1 : issue.getLevel();
            if (level == Dict.LEVEL_HIGH) {
                high++;
            } else if (level == Dict.LEVEL_MEDIUM) {
                medium++;
            } else {
                low++;
            }
            if (Dict.REVIEW_PENDING.equals(issue.getReviewStatus())) {
                pending++;
            }
            typeDist.merge(Dict.issueType(issue.getIssueType()), 1, Integer::sum);
        }

        List<AsyncTask> tasks = taskMapper.selectList(Wrappers.<AsyncTask>lambdaQuery()
                .eq(AsyncTask::getTaskType, "CHECK")
                .eq(AsyncTask::getStatus, Dict.TASK_SUCCESS)
                .orderByDesc(AsyncTask::getId)
                .last("LIMIT 50"));
        BigDecimal hitSum = BigDecimal.ZERO;
        int hitCount = 0;
        for (AsyncTask task : tasks) {
            Map<String, Object> result = JsonUtil.toMap(task.getResultJson());
            Object hitRate = result.get("hitRate");
            if (hitRate != null) {
                hitSum = hitSum.add(new BigDecimal(hitRate.toString()));
                hitCount++;
            }
        }
        // 清单可解析率直接取清单文件的解析结果均值（图纸解析不计入）
        List<ProjectFile> boqFiles = projectFileMapper.selectList(Wrappers.<ProjectFile>lambdaQuery()
                .eq(ProjectFile::getFileType, Dict.FILE_BOQ)
                .eq(ProjectFile::getParseStatus, Dict.PARSE_SUCCESS)
                .isNotNull(ProjectFile::getParseRate));
        BigDecimal parseSum = BigDecimal.ZERO;
        int parseCount = 0;
        for (ProjectFile file : boqFiles) {
            parseSum = parseSum.add(file.getParseRate());
            parseCount++;
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        projectMapper.selectList(Wrappers.<Project>lambdaQuery()
                        .orderByDesc(Project::getUpdateTime).last("LIMIT 5"))
                .forEach(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", p.getId());
                    item.put("projectNo", p.getProjectNo());
                    item.put("projectName", p.getProjectName());
                    item.put("status", p.getStatus());
                    item.put("statusName", Dict.projectStatus(p.getStatus()));
                    item.put("settleAmount", p.getSettleAmount());
                    item.put("updateTime", p.getUpdateTime());
                    recent.add(item);
                });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectTotal", projectTotal);
        data.put("checking", checking);
        data.put("checked", checked);
        data.put("reported", reported);
        data.put("issueTotal", issues.size());
        data.put("issueHigh", high);
        data.put("issueMedium", medium);
        data.put("issueLow", low);
        data.put("issuePending", pending);
        data.put("avgHitRate", average(hitSum, hitCount));
        data.put("avgParseRate", average(parseSum, parseCount));
        data.put("issueTypeDist", typeDist);
        data.put("recentProjects", recent);
        return R.ok(data);
    }

    private BigDecimal average(BigDecimal sum, int count) {
        return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }
}
