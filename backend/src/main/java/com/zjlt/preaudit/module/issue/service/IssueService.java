package com.zjlt.preaudit.module.issue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.common.util.ExcelUtil;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqItemMapper;
import com.zjlt.preaudit.module.issue.dto.IssueDtos;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 疑点清单（D1 生成与导出 / D2 行号回溯 / D3 复核）
 */
@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueMapper issueMapper;
    private final BoqItemMapper boqItemMapper;
    private final ProjectMapper projectMapper;

    public PageResult<IssueDtos.IssueVO> page(Long projectId, Integer level, String issueType,
                                              String reviewStatus, String keyword, long page, long size) {
        Page<Issue> p = new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size)));
        LambdaQueryWrapper<Issue> w = Wrappers.<Issue>lambdaQuery()
                .eq(projectId != null, Issue::getProjectId, projectId)
                .eq(level != null, Issue::getLevel, level)
                .eq(StringUtils.hasText(issueType), Issue::getIssueType, issueType)
                .eq(StringUtils.hasText(reviewStatus), Issue::getReviewStatus, reviewStatus)
                .orderByDesc(Issue::getLevel).orderByAsc(Issue::getProjectId).orderByAsc(Issue::getId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(Issue::getTitle, kw).or().like(Issue::getDetail, kw)
                    .or().like(Issue::getRefValue, kw));
        }
        issueMapper.selectPage(p, w);
        return PageResult.of(p, converter(p.getRecords()));
    }

    public IssueDtos.IssueVO get(Long id) {
        Issue issue = requireIssue(id);
        return toVOList(List.of(issue)).get(0);
    }

    /** 一键回溯定位（D2）：返回疑点 + 对应清单行 */
    public IssueDtos.IssueVO locate(Long id) {
        return get(id);
    }

    public IssueDtos.IssueStats stats(Long projectId) {
        List<Issue> issues = issueMapper.selectList(Wrappers.<Issue>lambdaQuery()
                .eq(projectId != null, Issue::getProjectId, projectId));
        int high = 0;
        int medium = 0;
        int low = 0;
        int pending = 0;
        int confirmed = 0;
        int rejected = 0;
        for (Issue i : issues) {
            if (i.getLevel() != null) {
                switch (i.getLevel()) {
                    case Dict.LEVEL_HIGH -> high++;
                    case Dict.LEVEL_MEDIUM -> medium++;
                    default -> low++;
                }
            }
            String status = i.getReviewStatus();
            if (Dict.REVIEW_CONFIRMED.equals(status)) {
                confirmed++;
            } else if (Dict.REVIEW_REJECTED.equals(status)) {
                rejected++;
            } else {
                pending++;
            }
        }
        return new IssueDtos.IssueStats(issues.size(), high, medium, low, pending, confirmed, rejected);
    }

    @Transactional
    public void review(Long id, IssueDtos.ReviewReq req) {
        Issue issue = requireIssue(id);
        applyReview(issue, req.action(), req.comment());
        issueMapper.updateById(issue);
    }

    @Transactional
    public int batchReview(IssueDtos.BatchReviewReq req) {
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BizException("请选择要复核的疑点");
        }
        List<Issue> issues = issueMapper.selectBatchIds(req.ids());
        for (Issue issue : issues) {
            applyReview(issue, req.action(), req.comment());
            issueMapper.updateById(issue);
        }
        return issues.size();
    }

    private void applyReview(Issue issue, String action, String comment) {
        String status = switch (action.toUpperCase()) {
            case "CONFIRM", "CONFIRMED" -> Dict.REVIEW_CONFIRMED;
            case "REJECT", "REJECTED" -> Dict.REVIEW_REJECTED;
            case "RESET", "PENDING" -> Dict.REVIEW_PENDING;
            default -> throw new BizException("不支持的复核动作：" + action);
        };
        issue.setReviewStatus(status);
        issue.setReviewComment(comment);
        issue.setReviewerId(UserContext.userId());
        issue.setReviewTime(LocalDateTime.now());
    }

    public void export(Long projectId, Integer level, String issueType, String reviewStatus,
                       HttpServletResponse response) {
        List<Issue> issues = issueMapper.selectList(Wrappers.<Issue>lambdaQuery()
                .eq(projectId != null, Issue::getProjectId, projectId)
                .eq(level != null, Issue::getLevel, level)
                .eq(StringUtils.hasText(issueType), Issue::getIssueType, issueType)
                .eq(StringUtils.hasText(reviewStatus), Issue::getReviewStatus, reviewStatus)
                .orderByDesc(Issue::getLevel).orderByAsc(Issue::getId));
        List<IssueDtos.IssueVO> vos = toVOList(issues);
        List<String> headers = List.of("序号", "级别", "类型", "疑点标题", "清单行号", "清单编号", "清单名称",
                "单位", "工程量", "综合单价", "合价", "判定明细", "AI 归因建议", "复核状态", "复核意见");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < vos.size(); i++) {
            IssueDtos.IssueVO v = vos.get(i);
            rows.add(List.of(
                    String.valueOf(i + 1),
                    nvl(v.levelName()),
                    nvl(v.issueTypeName()),
                    nvl(v.title()),
                    v.rowNo() == null ? "" : String.valueOf(v.rowNo()),
                    nvl(v.itemCode()),
                    nvl(v.itemName()),
                    nvl(v.unit()),
                    plain(v.quantity()),
                    plain(v.unitPrice()),
                    plain(v.totalPrice()),
                    nvl(v.detail()),
                    nvl(v.aiNote()),
                    nvl(v.reviewStatusName()),
                    nvl(v.reviewComment())));
        }
        String projectName = projectId == null ? "全部项目" : projectName(projectId);
        ExcelUtil.writeToResponse(response, "预审疑点清单-" + projectName + ".xlsx", "疑点清单", headers, rows);
    }

    private String plain(java.math.BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String projectName(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        return p == null ? String.valueOf(projectId) : p.getProjectName();
    }

    private Issue requireIssue(Long id) {
        Issue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BizException(404, "疑点不存在：" + id);
        }
        return issue;
    }

    private List<IssueDtos.IssueVO> toVOList(List<Issue> issues) {
        Function<Issue, IssueDtos.IssueVO> converter = converter(issues);
        return issues.stream().map(converter).toList();
    }

    private Function<Issue, IssueDtos.IssueVO> converter(List<Issue> issues) {
        Set<Long> projectIds = issues.stream().map(Issue::getProjectId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> itemIds = issues.stream().map(Issue::getBoqItemId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> projectNames = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Project::getProjectName));
        Map<Long, BoqItem> items = itemIds.isEmpty() ? Map.of()
                : boqItemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(BoqItem::getId, Function.identity()));
        return issue -> {
            BoqItem item = issue.getBoqItemId() == null ? null : items.get(issue.getBoqItemId());
            Integer rowNo = item != null ? item.getRowNo()
                    : (issue.getRefValue() != null && issue.getRefValue().matches("\\d+")
                    ? Integer.valueOf(issue.getRefValue()) : null);
            return new IssueDtos.IssueVO(issue.getId(), issue.getProjectId(),
                    projectNames.get(issue.getProjectId()), issue.getBoqItemId(), issue.getIssueType(),
                    Dict.issueType(issue.getIssueType()), issue.getLevel(), Dict.level(issue.getLevel()),
                    issue.getRuleCode(), issue.getRefType(), issue.getRefValue(), issue.getTitle(),
                    issue.getDetail(), issue.getAiNote(), issue.getAiModel(), issue.getReviewStatus(),
                    Dict.reviewStatus(issue.getReviewStatus()), issue.getReviewTime(), issue.getReviewComment(),
                    rowNo, item == null ? null : item.getItemCode(), item == null ? null : item.getItemName(),
                    item == null ? null : item.getUnit(), item == null ? null : item.getQuantity(),
                    item == null ? null : item.getUnitPrice(), item == null ? null : item.getTotalPrice(),
                    issue.getAiReferences());
        };
    }
}
