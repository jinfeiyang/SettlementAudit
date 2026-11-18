package com.zjlt.preaudit.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqItemMapper;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.dto.ProjectDtos;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.entity.ProjectFile;
import com.zjlt.preaudit.module.project.mapper.ProjectFileMapper;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.report.entity.Report;
import com.zjlt.preaudit.module.report.mapper.ReportMapper;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 预审项目与资料管理（A4）+ 主链路状态流转（F1）
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectFileMapper projectFileMapper;
    private final BoqItemMapper boqItemMapper;
    private final IssueMapper issueMapper;
    private final ReportMapper reportMapper;
    private final AsyncTaskMapper asyncTaskMapper;

    public PageResult<ProjectDtos.ProjectVO> page(String keyword, String status, String engType, long page, long size) {
        Page<Project> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        LambdaQueryWrapper<Project> w = Wrappers.<Project>lambdaQuery()
                .eq(StringUtils.hasText(status), Project::getStatus, status)
                .eq(StringUtils.hasText(engType), Project::getEngType, engType)
                .orderByDesc(Project::getCreateTime);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(Project::getProjectName, kw).or().like(Project::getProjectNo, kw));
        }
        projectMapper.selectPage(p, w);
        return PageResult.of(p, this::toVO);
    }

    @Transactional
    public ProjectDtos.ProjectVO create(ProjectDtos.SaveReq req) {
        Project project = new Project();
        // 项目编号不唯一：同一个项目编号下可以有多张订单
        project.setProjectNo(StringUtils.hasText(req.projectNo()) ? req.projectNo().trim() : generateProjectNo());
        project.setProjectName(req.projectName().trim());
        project.setEngType(normalizeEngType(req.engType()));
        applyBusinessFields(project, req);
        project.setStatus(Dict.PROJ_DRAFT);
        project.setOwnerUserId(UserContext.userId());
        projectMapper.insert(project);
        return toVO(project);
    }

    @Transactional
    public ProjectDtos.ProjectVO update(Long id, ProjectDtos.SaveReq req) {
        Project project = requireProject(id);
        if (Dict.PROJ_ARCHIVED.equals(project.getStatus())) {
            throw new BizException("已归档项目不可修改");
        }
        applyProjectNo(project, req.projectNo());
        project.setProjectName(req.projectName().trim());
        project.setEngType(normalizeEngType(req.engType()));
        applyBusinessFields(project, req);
        projectMapper.updateById(project);
        return toVO(project);
    }

    @Transactional
    public void delete(Long id) {
        Project project = requireProject(id);
        if (Dict.PROJ_CHECKING.equals(project.getStatus())) {
            throw new BizException("项目正在核查中，暂不可删除");
        }
        projectMapper.deleteById(id);
        projectFileMapper.delete(Wrappers.<ProjectFile>lambdaQuery().eq(ProjectFile::getProjectId, id));
        boqItemMapper.delete(Wrappers.<BoqItem>lambdaQuery().eq(BoqItem::getProjectId, id));
        issueMapper.delete(Wrappers.<Issue>lambdaQuery().eq(Issue::getProjectId, id));
        reportMapper.delete(Wrappers.<Report>lambdaQuery().eq(Report::getProjectId, id));
    }

    public ProjectDtos.ProjectVO get(Long id) {
        return toVO(requireProject(id));
    }

    public Project requireProject(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException(404, "项目不存在：" + id);
        }
        return project;
    }

    /** 状态流转（F1）：按主链路校验，非法流转直接拒绝 */
    @Transactional
    public void transit(Long id, String target) {
        Project project = requireProject(id);
        String from = project.getStatus();
        if (from.equals(target)) {
            return;
        }
        if (!Dict.canTransit(from, target)) {
            throw new BizException("非法的状态流转：" + Dict.projectStatus(from) + " → " + Dict.projectStatus(target));
        }
        project.setStatus(target);
        projectMapper.updateById(project);
    }

    /** 项目详情概览：主链路指标（D1 疑点分级、解析率、命中率、报告状态） */
    public ProjectDtos.Overview overview(Long id) {
        Project project = requireProject(id);
        int fileCount = count(projectFileMapper.selectCount(
                Wrappers.<ProjectFile>lambdaQuery().eq(ProjectFile::getProjectId, id)));
        // 取费行是导入时按规则生成的计算结果，清单页不逐行展示，条数也不计入清单行数，
        // 否则页签写着 42 条、表格里只有 25 行
        int boqCount = count(boqItemMapper.selectCount(
                Wrappers.<BoqItem>lambdaQuery()
                        .eq(BoqItem::getProjectId, id)
                        .ne(BoqItem::getItemType, Dict.BOQ_FEE)));
        int high = issueCount(id, Dict.LEVEL_HIGH, null);
        int medium = issueCount(id, Dict.LEVEL_MEDIUM, null);
        int low = issueCount(id, Dict.LEVEL_LOW, null);
        int pending = issueCount(id, null, Dict.REVIEW_PENDING);
        int confirmed = issueCount(id, null, Dict.REVIEW_CONFIRMED);
        int rejected = issueCount(id, null, Dict.REVIEW_REJECTED);

        BigDecimal boqTotal = boqItemMapper.sumTotalPrice(id);
        if (boqTotal == null) {
            boqTotal = BigDecimal.ZERO;
        }
        BigDecimal hitRate = null;
        BigDecimal checkAmount = null;
        BigDecimal feeDeviation = null;
        java.time.LocalDateTime lastCheckTime = null;
        AsyncTask task = asyncTaskMapper.selectOne(Wrappers.<AsyncTask>lambdaQuery()
                .eq(AsyncTask::getProjectId, id)
                .eq(AsyncTask::getTaskType, "CHECK")
                .eq(AsyncTask::getStatus, Dict.TASK_SUCCESS)
                .orderByDesc(AsyncTask::getId)
                .last("LIMIT 1"));
        if (task != null) {
            lastCheckTime = task.getUpdateTime();
            Map<String, Object> result = JsonUtil.toMap(task.getResultJson());
            hitRate = toDecimal(result.get("hitRate"));
            checkAmount = toDecimal(result.get("checkAmount"));
            feeDeviation = toDecimal(result.get("feeDeviation"));
        }
        Report report = reportMapper.selectOne(Wrappers.<Report>lambdaQuery()
                .eq(Report::getProjectId, id)
                .orderByDesc(Report::getId)
                .last("LIMIT 1"));
        return new ProjectDtos.Overview(project.getId(), project.getProjectName(), project.getStatus(),
                Dict.projectStatus(project.getStatus()), fileCount, boqCount, high + medium + low,
                high, medium, low, pending, confirmed, rejected,
                project.getSettleAmount(), boqTotal, checkAmount, hitRate, feeDeviation,
                report == null ? null : report.getId(), report == null ? null : report.getStatus(), lastCheckTime);
    }

    private int issueCount(Long projectId, Integer level, String reviewStatus) {
        return count(issueMapper.selectCount(Wrappers.<Issue>lambdaQuery()
                .eq(Issue::getProjectId, projectId)
                .eq(level != null, Issue::getLevel, level)
                .eq(reviewStatus != null, Issue::getReviewStatus, reviewStatus)));
    }

    private int count(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeEngType(String engType) {
        if (!StringUtils.hasText(engType)) {
            return null;
        }
        String v = engType.trim().toUpperCase();
        if (!List.of(Dict.ENG_LINE, Dict.ENG_DEVICE).contains(v)) {
            throw new BizException("工程类型仅支持 LINE（线路）或 DEVICE（设备安装）");
        }
        return v;
    }

    private String generateProjectNo() {
        long count = projectMapper.selectCount(null);
        return "YS" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%03d", count + 1);
    }

    /** 项目编号允许编辑：留空则保持原编号，不做重号校验（同一编号下可有多张订单） */
    private void applyProjectNo(Project project, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        project.setProjectNo(raw.trim());
    }

    /** 工程背景信息（合同/订单编号、参建单位、施工费）统一在这里落库，空串按 null 存 */
    private void applyBusinessFields(Project project, ProjectDtos.SaveReq req) {
        project.setContractNo(blankToNull(req.contractNo()));
        project.setOrderNo(blankToNull(req.orderNo()));
        project.setBuilderUnit(blankToNull(req.builderUnit()));
        project.setSupervisorUnit(blankToNull(req.supervisorUnit()));
        project.setConstructionFee(req.constructionFee());
        project.setCity(blankToNull(req.city()));
        project.setSettleAmount(req.settleAmount());
        project.setRemark(blankToNull(req.remark()));
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ProjectDtos.ProjectVO toVO(Project p) {
        return new ProjectDtos.ProjectVO(p.getId(), p.getProjectNo(), p.getProjectName(), p.getEngType(),
                Dict.engType(p.getEngType()), p.getContractNo(), p.getOrderNo(), p.getBuilderUnit(),
                p.getSupervisorUnit(), p.getConstructionFee(), p.getCity(), p.getSettleAmount(), p.getStatus(),
                Dict.projectStatus(p.getStatus()), p.getRemark(), p.getCreateTime(), p.getUpdateTime());
    }
}
