package com.zjlt.preaudit.module.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.ai.client.AiServiceClient;
import com.zjlt.preaudit.module.system.service.AiSettingsService;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqItemMapper;
import com.zjlt.preaudit.module.issue.dto.IssueDtos;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.issue.mapper.IssueMapper;
import com.zjlt.preaudit.module.issue.service.IssueService;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.mapper.ProjectMapper;
import com.zjlt.preaudit.module.project.service.ProjectService;
import com.zjlt.preaudit.module.report.dto.ReportDtos;
import com.zjlt.preaudit.module.report.entity.Report;
import com.zjlt.preaudit.module.report.mapper.ReportMapper;
import com.zjlt.preaudit.module.task.entity.AsyncTask;
import com.zjlt.preaudit.module.task.mapper.AsyncTaskMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 预审报告：模板装配 + AI 成文（C3）、管理与确认（D4）、编辑与 Word 导出（D5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final BoqItemMapper boqItemMapper;
    private final IssueMapper issueMapper;
    private final IssueService issueService;
    private final AsyncTaskMapper asyncTaskMapper;
    private final AiServiceClient aiClient;
    private final AiSettingsService aiSettingsService;

    /** 生成报告初稿：确定性数据装配 + AI 成文（不可用时模板成文） */
    @Transactional
    public ReportDtos.ReportVO generate(Long projectId) {
        Project project = projectService.requireProject(projectId);
        AsyncTask task = asyncTaskMapper.selectOne(Wrappers.<AsyncTask>lambdaQuery()
                .eq(AsyncTask::getProjectId, projectId)
                .eq(AsyncTask::getTaskType, "CHECK")
                .eq(AsyncTask::getStatus, Dict.TASK_SUCCESS)
                .orderByDesc(AsyncTask::getId).last("LIMIT 1"));
        if (task == null) {
            throw new BizException("该项目暂无成功核查记录，请先执行自动核查");
        }
        IssueDtos.IssueStats stats = issueService.stats(projectId);
        List<BoqItem> items = boqItemMapper.selectList(Wrappers.<BoqItem>lambdaQuery()
                .eq(BoqItem::getProjectId, projectId).orderByAsc(BoqItem::getRowNo)).stream()
                .filter(i -> !Dict.isPricedExtra(i.getItemType())).toList();
        Map<String, Object> result = JsonUtil.toMap(task.getResultJson());

        List<Issue> topIssues = issueMapper.selectList(Wrappers.<Issue>lambdaQuery()
                .eq(Issue::getProjectId, projectId)
                .ge(Issue::getLevel, Dict.LEVEL_MEDIUM)
                .orderByDesc(Issue::getLevel)
                .last("LIMIT 30"));

        List<ReportDtos.Section> sections = new ArrayList<>();
        sections.add(ReportDtos.Section.text("一、项目概况", overviewText(project, items.size(), stats, result)));
        sections.add(ReportDtos.Section.text("二、预审依据与方法",
                "1. 依据：现行通信工程预算定额、通信工程费用定额及配套取费标准（规则库版本由「规则库」模块维护并带生效时间）。\n"
                        + "2. 方法：工程量清单导入与标准化 → 定额套用三级核查（精确匹配 / 特征语义召回 / 换算系数校验）→ "
                        + "取费基数与费率核查 → 疑点三级分级输出（高：确定性偏差；中：疑似需复核；低：提示性）。\n"
                        + "3. 说明：金额与结论均由确定性核算内核判定；AI 仅用于生成归因解释与文字建议，不修改金额、不下最终结论。"));

        sections.add(ReportDtos.Section.table("三、核查结果汇总", List.of("核查指标", "结果"),
                List.of(
                        List.of("清单项数", String.valueOf(items.size())),
                        List.of("疑点合计", String.valueOf(stats.total())),
                        List.of("高风险疑点（确定偏差）", String.valueOf(stats.high())),
                        List.of("中风险疑点（需复核）", String.valueOf(stats.medium())),
                        List.of("低风险疑点（提示性）", String.valueOf(stats.low())),
                        List.of("定额前三命中率", percent(result.get("hitRate"))),
                        List.of("清单直接费合计（元）", money(result.get("directCost"))),
                        List.of("应计取费合计（元）", money(result.get("feeTotal"))),
                        List.of("核算应计金额（元）", money(result.get("checkAmount"))),
                        List.of("结算送审金额（元）", project.getSettleAmount() == null ? "未填写"
                                : project.getSettleAmount().toPlainString()),
                        List.of("取费偏差", percentRaw(result.get("feeDeviation"))))));

        List<List<String>> issueRows = new ArrayList<>();
        for (int i = 0; i < topIssues.size(); i++) {
            Issue issue = topIssues.get(i);
            issueRows.add(List.of(String.valueOf(i + 1), Dict.level(issue.getLevel()),
                    Dict.issueType(issue.getIssueType()), nvl(issue.getRefValue()), nvl(issue.getTitle()),
                    Dict.reviewStatus(issue.getReviewStatus())));
        }
        if (issueRows.isEmpty()) {
            issueRows.add(List.of("-", "-", "-", "-", "无中高风险疑点", "-"));
        }
        sections.add(ReportDtos.Section.table("四、主要疑点明细（高/中风险，前 30 条）",
                List.of("序号", "级别", "类型", "清单行号", "疑点标题", "复核状态"), issueRows));

        String advice = adviceText(stats, result);
        String aiModel = null;
        // 报告 AI 成文受独立开关控制；关闭时直接用模板文案，不调用模型
        if (aiSettingsService.isReportAiEnabled()) {
            Optional<AiServiceClient.ChatResp> ai = aiClient.chat("REPORT",
                    "请基于以下预审核查统计给出不超过 200 字的处理建议，面向造价人员，仅做建议与解释，"
                            + "不得编造金额、不得下审计结论。直接输出纯文本，不要输出 JSON 或代码围栏。",
                    Map.of("projectName", project.getProjectName(),
                            "engType", Dict.engType(project.getEngType()),
                            "issueHigh", stats.high(), "issueMedium", stats.medium(), "issueLow", stats.low(),
                            "hitRate", String.valueOf(result.get("hitRate")),
                            "topIssues", topIssues.stream().limit(10).map(Issue::getTitle).toList()));
            if (ai.isPresent()) {
                advice = plainText(ai.get().content());
                aiModel = ai.get().model();
            }
        }
        sections.add(ReportDtos.Section.text("五、处理建议", advice));
        sections.add(ReportDtos.Section.text("六、复核情况",
                String.format("疑点复核进度：待复核 %d 条，已确认 %d 条，已驳回 %d 条。%n"
                                + "复核结论以项目经理确认意见为准；本报告为预审初稿，不替代正式审计结论。",
                        stats.pending(), stats.confirmed(), stats.rejected())));

        String content = JsonUtil.toJson(new ReportDtos.ReportContent(sections));
        Report report = new Report();
        report.setProjectId(projectId);
        report.setTitle(project.getProjectName() + " 结算预审报告");
        report.setContent(content);
        report.setAiModel(aiModel);
        report.setStatus(Dict.REPORT_DRAFT);
        report.setVersion(nextVersion(projectId));
        reportMapper.insert(report);

        try {
            projectService.transit(projectId, Dict.PROJ_REPORTED);
        } catch (BizException ignored) {
            // 状态不允许流转时仅生成报告，不改项目状态
        }
        return toVO(report);
    }

    public List<ReportDtos.ReportVO> list(Long projectId) {
        return reportMapper.selectList(Wrappers.<Report>lambdaQuery()
                        .eq(projectId != null, Report::getProjectId, projectId)
                        .orderByDesc(Report::getId))
                .stream().map(this::toVO).toList();
    }

    public ReportDtos.ReportVO get(Long id) {
        return toVO(requireReport(id));
    }

    /** 删除报告：已确认的报告为正式结论，不允许删除 */
    @Transactional
    public void delete(Long id) {
        Report report = requireReport(id);
        if (Dict.REPORT_CONFIRMED.equals(report.getStatus())) {
            throw new BizException("报告已确认，不能删除");
        }
        reportMapper.deleteById(id);
    }

    /** 编辑报告（标题/结构化内容） */
    @Transactional
    public ReportDtos.ReportVO update(Long id, ReportDtos.SaveReq req) {
        Report report = requireReport(id);
        if (Dict.REPORT_CONFIRMED.equals(report.getStatus())) {
            throw new BizException("报告已确认，若需修改请重新生成初稿");
        }
        if (StringUtils.hasText(req.title())) {
            report.setTitle(req.title().trim());
        }
        if (StringUtils.hasText(req.content())) {
            JsonUtil.fromJson(req.content(), ReportDtos.ReportContent.class);
            report.setContent(req.content());
        }
        reportMapper.updateById(report);
        return toVO(report);
    }

    /** 项目经理确认（D4）：仅 MANAGER 角色可确认 */
    @Transactional
    public ReportDtos.ReportVO confirm(Long id) {
        if (!"MANAGER".equals(UserContext.roleCode())) {
            throw new BizException(403, "仅项目经理可确认预审报告");
        }
        Report report = requireReport(id);
        if (Dict.REPORT_CONFIRMED.equals(report.getStatus())) {
            return toVO(report);
        }
        report.setStatus(Dict.REPORT_CONFIRMED);
        report.setConfirmUserId(UserContext.userId());
        report.setConfirmTime(java.time.LocalDateTime.now());
        reportMapper.updateById(report);
        return toVO(report);
    }

    /** 导出 Word（D5） */
    public void exportWord(Long id, HttpServletResponse response) {
        Report report = requireReport(id);
        Project project = projectMapper.selectById(report.getProjectId());
        ReportDtos.ReportContent content = JsonUtil.fromJson(report.getContent(), ReportDtos.ReportContent.class);
        List<ReportDtos.Section> sections = content == null || content.sections() == null
                ? List.of() : content.sections();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph titleParagraph = doc.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setText(nvl(report.getTitle()));
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("宋体");

            XWPFParagraph meta = doc.createParagraph();
            meta.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun metaRun = meta.createRun();
            metaRun.setFontSize(9);
            metaRun.setFontFamily("宋体");
            metaRun.setText(String.format("项目编号：%s    工程类型：%s    版本：V%d    生成时间：%s",
                    project == null ? "" : nvl(project.getProjectNo()),
                    project == null ? "" : Dict.engType(project.getEngType()),
                    report.getVersion(),
                    report.getUpdateTime() == null ? ""
                            : report.getUpdateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));

            for (ReportDtos.Section section : sections) {
                XWPFParagraph heading = doc.createParagraph();
                XWPFRun headingRun = heading.createRun();
                headingRun.setText(nvl(section.title()));
                headingRun.setBold(true);
                headingRun.setFontSize(13);
                headingRun.setFontFamily("宋体");
                if ("table".equals(section.type()) && section.columns() != null) {
                    writeTable(doc, section);
                } else {
                    writeText(doc, section.text());
                }
            }
            String fileName = report.getTitle() + "-V" + report.getVersion() + ".docx";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            OutputStream out = response.getOutputStream();
            doc.write(out);
            out.flush();

            if (Dict.REPORT_CONFIRMED.equals(report.getStatus())) {
                report.setStatus(Dict.REPORT_EXPORTED);
                reportMapper.updateById(report);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("报告导出失败 id={}", id, e);
            throw new BizException("报告导出失败：" + e.getMessage());
        }
    }

    private void writeTable(XWPFDocument doc, ReportDtos.Section section) {
        List<String> columns = section.columns();
        List<List<String>> rows = section.rows() == null ? List.of() : section.rows();
        XWPFTable table = doc.createTable(rows.size() + 1, columns.size());
        table.setWidth("100%");
        // 行高放宽：默认行高贴合 9 磅字号显得挤，设最小 18 磅（360 twip），
        // 用 AT_LEAST 让内容较长时仍能自动撑高，不会被固定高度截断。
        for (XWPFTableRow row : table.getRows()) {
            row.setHeight(360);
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
        }
        for (int c = 0; c < columns.size(); c++) {
            setCellText(table.getRow(0), c, columns.get(c), true);
        }
        for (int r = 0; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                setCellText(table.getRow(r + 1), c, c < values.size() ? values.get(c) : "", false);
            }
        }
        doc.createParagraph();
    }

    private void setCellText(XWPFTableRow row, int index, String text, boolean bold) {
        if (row.getCell(index) == null) {
            row.addNewTableCell();
        }
        XWPFTableCell cell = row.getCell(index);
        // 内容水平、垂直都居中
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(nvl(text));
        run.setBold(bold);
        run.setFontSize(9);
        run.setFontFamily("宋体");
    }

    private void writeText(XWPFDocument doc, String text) {
        String[] lines = nvl(text).split("\\n");
        for (String line : lines) {
            XWPFParagraph p = doc.createParagraph();
            p.setFirstLineIndent(420);
            XWPFRun run = p.createRun();
            run.setText(line);
            run.setFontSize(11);
            run.setFontFamily("宋体");
        }
    }

    private String overviewText(Project project, int itemCount, IssueDtos.IssueStats stats,
                                Map<String, Object> result) {
        return String.format("项目名称：%s；项目编号：%s；工程类型：%s；地市：%s。%n"
                        + "结算送审金额：%s 元；清单项数：%d 项。%n"
                        + "本次预审共输出疑点 %d 条，其中高风险 %d 条、中风险 %d 条、低风险 %d 条。%n"
                        + "核查时间：%s。",
                nvl(project.getProjectName()), nvl(project.getProjectNo()),
                Dict.engType(project.getEngType()), nvl(project.getCity()),
                project.getSettleAmount() == null ? "未填写" : project.getSettleAmount().toPlainString(),
                itemCount, stats.total(), stats.high(), stats.medium(), stats.low(),
                java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    private String adviceText(IssueDtos.IssueStats stats, Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        sb.append("1. 高风险疑点 ").append(stats.high())
                .append(" 条为确定性偏差（定额未匹配、工程量或合价异常、取费偏差过大），建议逐条核对清单原文、结算书与图纸后要求整改。\n");
        sb.append("2. 中风险疑点 ").append(stats.medium())
                .append(" 条为疑似需复核项（语义召回定额、计量单位换算、取费偏差），建议结合项目特征与定额子目说明人工确认。\n");
        sb.append("3. 定额前三命中率 ")
                .append(percent(result.get("hitRate")))
                .append("，低于 85% 时建议补充规则库定额子目与特征关键词，提升自动匹配质量。\n");
        sb.append("4. 复核完成后由项目经理确认报告结论；AI 生成内容仅为文字建议，不构成审计结论。");
        return sb.toString();
    }

    private String percent(Object value) {
        if (value == null) {
            return "-";
        }
        return value + "%";
    }

    private String percentRaw(Object value) {
        if (value == null) {
            return "-";
        }
        try {
            BigDecimal v = new BigDecimal(value.toString()).multiply(BigDecimal.valueOf(100));
            return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
        } catch (NumberFormatException e) {
            return value.toString();
        }
    }

    private String money(Object value) {
        if (value == null) {
            return "-";
        }
        return new BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private int nextVersion(Long projectId) {
        Report last = reportMapper.selectOne(Wrappers.<Report>lambdaQuery()
                .eq(Report::getProjectId, projectId)
                .orderByDesc(Report::getVersion).last("LIMIT 1"));
        return last == null ? 1 : last.getVersion() + 1;
    }

    private Report requireReport(Long id) {
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(404, "报告不存在：" + id);
        }
        return report;
    }

    private String nvl(String s) {
        return TextUtil.nullToEmpty(s);
    }

    /** 兜底去掉模型可能输出的 ```json 代码围栏，取 advice/suggestion/attribution 文本字段 */
    private String plainText(String content) {
        String text = nvl(content).trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(nl + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                for (String key : new String[]{"advice", "suggestion", "attribution", "content", "text"}) {
                    com.fasterxml.jackson.databind.JsonNode value = node.get(key);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        return value.asText().trim();
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 或解析失败时保留原文本
            }
        }
        return text;
    }

    private ReportDtos.ReportVO toVO(Report r) {
        Project project = projectMapper.selectById(r.getProjectId());
        return new ReportDtos.ReportVO(r.getId(), r.getProjectId(), project == null ? null : project.getProjectName(),
                r.getTitle(), r.getContent(), r.getAiModel(), r.getStatus(),
                Dict.REPORT_CONFIRMED.equals(r.getStatus()) ? "已确认"
                        : Dict.REPORT_EXPORTED.equals(r.getStatus()) ? "已导出" : "草稿",
                r.getVersion(), r.getConfirmTime(), r.getCreateTime(), r.getUpdateTime());
    }
}
