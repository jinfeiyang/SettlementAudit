package com.zjlt.preaudit.module.check.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingEdit;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingMatch;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingMerge;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.boq.mapper.BoqDrawingEditMapper;
import com.zjlt.preaudit.module.boq.mapper.BoqDrawingMatchMapper;
import com.zjlt.preaudit.module.boq.mapper.BoqDrawingMergeMapper;
import com.zjlt.preaudit.module.issue.entity.Issue;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.project.entity.ProjectFile;
import com.zjlt.preaudit.module.project.mapper.ProjectFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B3 图纸-清单核对：用竣工图解析出的材料表与清单逐行比量。
 *
 * <p>图纸侧数据来自 {@code proj_file.parse_result}（AI 服务 /parse/dxf 的结果）里的
 * {@code materialSummary}：材料归纳（名称/规格/单位/数量），跨图纸按名称+规格汇总。
 *
 * <p>结论必须落在具体清单行上：每行都先找图纸材料表里的对应材料，找不到就出
 * 「图纸无对应」，找到且单位可换算再比数量。定额子目行同样参与这一步——清单页的
 * 「图纸匹配」列对定额子目行也会标「图纸无对应」，两边口径必须一致，否则界面上
 * 标红的行在疑点里查不到对应记录。
 *
 * <p>不再拿图纸光缆总量跟清单光缆总量比一个综合偏差：那条结论落不到任何清单行上，
 * 与行级的数量偏差结论重复，复核时也无法定位到具体清单行，属于自造的疑点。
 * 图纸侧没解析出任何材料时整体跳过，避免把"解析失败"当成"图纸没有"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawingCheckService {

    private final ProjectFileMapper projectFileMapper;
    private final BoqDrawingMatchMapper drawingMatchMapper;
    private final BoqDrawingMergeMapper drawingMergeMapper;
    private final BoqDrawingEditMapper drawingEditMapper;

    /** 图纸数量相对清单数量的偏差告警阈值（0~1） */
    @Value("${preaudit.check.drawing-deviation-threshold:0.05}")
    private BigDecimal deviationThreshold = new BigDecimal("0.05");

    /** 图纸偏小到这个幅度算高风险（0~1） */
    @Value("${preaudit.check.drawing-high-threshold:0.20}")
    private BigDecimal highThreshold = new BigDecimal("0.20");

    /** 名称相似度低于该值认为图纸里没有对应材料 */
    @Value("${preaudit.check.drawing-match-threshold:0.55}")
    private double matchThreshold = 0.55;

    /** 单次核查最多出多少条图纸疑点，避免材料行很多时刷屏 */
    private static final int MAX_ISSUES = 200;

    /**
     * 计件单位：同一件设备/器材，清单写「套」、图纸写「个」是同一批数量，按 1:1 比。
     * 与前端 match.ts 的 unitFamily 口径保持一致，否则界面标了匹配、疑点里却是「图纸无对应」。
     */
    private static final Set<String> COUNT_UNITS = Set.of(
            "个", "台", "套", "架", "组", "块", "面", "只", "件", "条", "根", "处", "端", "芯",
            "盘", "卷", "座");

    /** 「24芯」「24 芯」；「288芯以上」是定额的范围写法，不是具体芯数 */
    private static final Pattern CORE_PATTERN = Pattern.compile("(\\d{1,3})\\s*芯(?!\\s*[及以]?[上下])");
    /** 光缆型号里的芯数：GYTS-48B1 / GYTA53-96B1.2 */
    private static final Pattern CORE_MODEL_PATTERN = Pattern.compile("(?i)(\\d{1,3})\\s*b\\s*\\d");

    /** 跨图纸汇总后的一行图纸材料 */
    record DrawingMaterial(String name, String spec, String unit, BigDecimal quantity,
                           List<String> files) {
    }

    public List<Issue> check(Project project, List<BoqItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ProjectFile> files = projectFileMapper.selectList(Wrappers.<ProjectFile>lambdaQuery()
                .eq(ProjectFile::getProjectId, project.getId())
                .in(ProjectFile::getFileType, Dict.FILE_DWG, Dict.FILE_DXF)
                .eq(ProjectFile::getParseStatus, Dict.PARSE_SUCCESS));

        Map<String, DrawingMaterial> materials = new HashMap<>();
        List<String> parsedFiles = new ArrayList<>();
        for (ProjectFile file : files) {
            Map<String, Object> root = readParseResult(file);
            if (root == null) {
                continue;
            }
            parsedFiles.add(file.getFileName());
            collectMaterials(root.get("materialSummary"), file.getFileName(), materials);
        }
        if (parsedFiles.isEmpty()) {
            log.info("图纸-清单核对跳过：项目 {} 没有可用的图纸解析结果", project.getId());
            return List.of();
        }

        // 人工结论优先：图纸材料的合并/改名/改量与清单行的手工匹配，都要在核查前叠加
        applyManualMaterialOverrides(project.getId(), materials);
        Map<Long, BoqDrawingMatch> manualMatches = new HashMap<>();
        for (BoqDrawingMatch match : drawingMatchMapper.selectList(Wrappers.<BoqDrawingMatch>lambdaQuery()
                .eq(BoqDrawingMatch::getProjectId, project.getId()))) {
            if (match.getBoqItemId() != null) {
                manualMatches.put(match.getBoqItemId(), match);
            }
        }

        List<Issue> issues = evaluate(project.getId(), items, materials, parsedFiles, manualMatches);
        log.info("图纸-清单核对完成 projectId={} 图纸 {} 张，材料 {} 种，疑点 {} 条",
                project.getId(), parsedFiles.size(), materials.size(), issues.size());
        return issues;
    }

    /**
     * 叠加图纸侧的人工结论，顺序与前端 DrawingBoqPanel 一致：
     * 先按原键改名改量 → 合并组求和（合并行以 groupKey 为键）→ 再按合并键改名改量。
     * 不叠加的话，界面上看到的是人工处理后的行，疑点却按 AI 原始数据算，结论对不上。
     */
    private void applyManualMaterialOverrides(Long projectId, Map<String, DrawingMaterial> materials) {
        List<BoqDrawingEdit> edits = drawingEditMapper.selectList(Wrappers.<BoqDrawingEdit>lambdaQuery()
                .eq(BoqDrawingEdit::getProjectId, projectId));
        List<BoqDrawingMerge> merges = drawingMergeMapper.selectList(Wrappers.<BoqDrawingMerge>lambdaQuery()
                .eq(BoqDrawingMerge::getProjectId, projectId));

        for (BoqDrawingEdit edit : edits) {
            applyMaterialEdit(materials, edit);
        }

        Map<String, List<String>> membersByGroup = new LinkedHashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        for (BoqDrawingMerge merge : merges) {
            if (merge.getGroupKey() == null || merge.getMemberKey() == null) {
                continue;
            }
            membersByGroup.computeIfAbsent(merge.getGroupKey(), k -> new ArrayList<>()).add(merge.getMemberKey());
            if (merge.getDisplayName() != null && !merge.getDisplayName().isBlank()) {
                displayNames.put(merge.getGroupKey(), merge.getDisplayName().trim());
            }
        }
        for (Map.Entry<String, List<String>> group : membersByGroup.entrySet()) {
            List<String> keys = group.getValue();
            if (keys.size() < 2) {
                continue;
            }
            List<DrawingMaterial> members = new ArrayList<>();
            for (String key : keys) {
                DrawingMaterial member = materials.get(key);
                if (member != null) {
                    members.add(member);
                }
            }
            if (members.size() < 2) {
                continue;
            }
            DrawingMaterial first = members.get(0);
            BigDecimal quantity = BigDecimal.ZERO;
            Set<String> files = new LinkedHashSet<>();
            for (DrawingMaterial member : members) {
                quantity = quantity.add(member.quantity());
                files.addAll(member.files());
            }
            String name = displayNames.getOrDefault(group.getKey(), first.name());
            keys.forEach(materials::remove);
            materials.put(group.getKey(),
                    new DrawingMaterial(name, first.spec(), first.unit(), quantity, new ArrayList<>(files)));
        }

        // 合并行本身被人工改名/改量时，前端也是合并后再覆盖一次
        for (BoqDrawingEdit edit : edits) {
            applyMaterialEdit(materials, edit);
        }
    }

    private void applyMaterialEdit(Map<String, DrawingMaterial> materials, BoqDrawingEdit edit) {
        DrawingMaterial current = materials.get(edit.getMaterialKey());
        if (current == null) {
            return;
        }
        String name = edit.getName() == null || edit.getName().isBlank()
                ? current.name() : edit.getName().trim();
        String spec = edit.getSpec() == null ? current.spec() : edit.getSpec();
        String unit = edit.getUnit() == null ? current.unit() : edit.getUnit();
        BigDecimal quantity = edit.getQuantity() == null ? current.quantity() : edit.getQuantity();
        materials.put(edit.getMaterialKey(), new DrawingMaterial(name, spec, unit, quantity, current.files()));
    }

    /** 比对本体：脱离数据库的纯计算，便于单测 */
    List<Issue> evaluate(Long projectId, List<BoqItem> items, Map<String, DrawingMaterial> materials,
                         List<String> parsedFiles) {
        return evaluate(projectId, items, materials, parsedFiles, Map.of());
    }

    List<Issue> evaluate(Long projectId, List<BoqItem> items, Map<String, DrawingMaterial> materials,
                         List<String> parsedFiles, Map<Long, BoqDrawingMatch> manualMatches) {
        List<Issue> issues = new ArrayList<>();
        checkMaterials(projectId, items, materials, parsedFiles, issues, manualMatches);
        return issues;
    }

    // ------------------------------------------------------------------
    // 材料/设备行：逐行找图纸材料表里对应的那一行
    // ------------------------------------------------------------------

    private void checkMaterials(Long projectId, List<BoqItem> items,
                                Map<String, DrawingMaterial> materials, List<String> parsedFiles,
                                List<Issue> issues, Map<Long, BoqDrawingMatch> manualMatches) {
        if (materials.isEmpty()) {
            return;
        }
        // 人工指定过的材料不再自动挂到别的清单行，与前端 applyMatchOverrides 的口径一致
        Set<String> manualKeys = new LinkedHashSet<>();
        for (BoqDrawingMatch match : manualMatches.values()) {
            if ("MATCH".equals(match.getManualType()) && match.getDrawingKey() != null
                    && !match.getDrawingKey().isBlank()) {
                manualKeys.add(match.getDrawingKey().trim());
            }
        }
        for (BoqItem item : items) {
            if (issues.size() >= MAX_ISSUES) {
                return;
            }
            // 工程量为空的行由 B0-QTY 单独报，这里没有可比的量
            if (item.getQuantity() == null) {
                continue;
            }
            // 定额子目行（含未标类别的行）也要出「图纸无对应」：清单页的「图纸匹配」列
            // 对这类行同样标红，两边结论必须对得上。
            // 命中材料后照常比数量，只要单位可换算（同口径）；单位不可比的才跳过。
            boolean quotaRow = item.getItemType() == null || Dict.BOQ_QUOTA.equals(item.getItemType());
            BoqDrawingMatch manual = item.getId() == null ? null : manualMatches.get(item.getId());
            if (manual != null && "UNMATCH".equals(manual.getManualType())) {
                continue;  // 人工取消匹配：这一行不再与任何图纸材料比量
            }
            DrawingMaterial best = null;
            double bestScore = 0d;
            if (manual != null && "MATCH".equals(manual.getManualType())
                    && manual.getDrawingKey() != null && !manual.getDrawingKey().isBlank()) {
                best = materials.get(manual.getDrawingKey().trim());
                if (best == null) {
                    continue;  // 人工指定的材料在图纸里已不存在（如重新解析后），不能拿自动结果顶上
                }
                bestScore = 1d;
            } else {
                for (Map.Entry<String, DrawingMaterial> entry : materials.entrySet()) {
                    if (manualKeys.contains(entry.getKey())) {
                        continue;
                    }
                    double score = score(item, entry.getValue());
                    if (score > bestScore) {
                        bestScore = score;
                        best = entry.getValue();
                    }
                }
            }
            String rowRef = item.getRowNo() == null ? "-" : String.valueOf(item.getRowNo());
            if (best == null || bestScore < matchThreshold) {
                String detail = quotaRow
                        ? String.format("清单第 %s 行「%s」是定额子目行，图纸材料表里没有同名条目"
                                        + "（已核对 %d 张图纸：%s）。请确认图纸材料表是否漏列该项用量，或清单是否多报。",
                                rowRef, item.getItemName(), parsedFiles.size(), joinFiles(parsedFiles))
                        : String.format("清单第 %s 行「%s」在图纸材料表里没有找到对应材料"
                                        + "（已核对 %d 张图纸：%s）。请确认图纸是否漏画该材料，或清单是否多报。",
                                rowRef, item.getItemName(), parsedFiles.size(), joinFiles(parsedFiles));
                issues.add(buildIssue(projectId, item, Dict.LEVEL_MEDIUM, "C1-NO-DRAWING",
                        "图纸无对应：" + TextUtil.truncate(item.getItemName(), 60), detail, parsedFiles));
                continue;
            }
            BigDecimal drawingQty = best.quantity();
            BigDecimal itemQty = item.getQuantity();
            BigDecimal factor = factorOf(item.getUnit(), best.unit());
            if (factor == null) {
                continue;  // 单位不同族（米 vs 个），数量不可比
            }
            itemQty = itemQty.multiply(factor);
            if (drawingQty.signum() <= 0 || itemQty.signum() <= 0) {
                continue;
            }
            BigDecimal rate = diffRate(drawingQty, itemQty);
            if (rate.abs().compareTo(deviationThreshold) < 0) {
                continue;
            }
            boolean less = rate.signum() < 0;
            // 分级只看偏差幅度与证据充分度，与「偏大/偏小」方向无关：
            // 达到高风险阈值（默认 20%）给高，达到告警阈值（默认 5%）给中。
            int level = rate.abs().compareTo(highThreshold) >= 0 ? Dict.LEVEL_HIGH : Dict.LEVEL_MEDIUM;
            String title = String.format("图纸数量比清单%s %s%%：%s", less ? "偏小" : "偏大",
                    rate.abs().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString(),
                    TextUtil.truncate(item.getItemName(), 40));
            String detail = String.format("清单第 %s 行「%s」数量 %s %s，图纸材料表「%s%s」数量 %s %s"
                            + "（来源：%s），图纸比清单%s %s%%。请核对图纸材料表与清单工程量。",
                    rowRef, item.getItemName(), plain(item.getQuantity()), TextUtil.nullToEmpty(item.getUnit()),
                    best.name(), best.spec().isBlank() ? "" : " " + best.spec(),
                    plain(drawingQty), TextUtil.nullToEmpty(best.unit()),
                    joinFiles(best.files()),
                    less ? "少" : "多",
                    rate.abs().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString());
            issues.add(buildIssue(projectId, item, level, "C1-QTY-" + (less ? "LESS" : "MORE"),
                    title, detail, best.files()));
        }
    }

    /**
     * 清单行 vs 图纸材料的匹配分：名称相似度 + 完全包含加成，单位/规格冲突直接排除。
     * 名称先过一道同物异名归一：图纸上的「新立光交」与清单「光缆交接箱」是同一件器材。
     */
    private double score(BoqItem item, DrawingMaterial material) {
        String itemName = TextUtil.canonicalName(item.getItemName());
        String drawName = TextUtil.canonicalName(material.name());
        if (itemName.isBlank() || drawName.isBlank()) {
            return 0d;
        }
        if (!unitConvertible(item.getUnit(), material.unit())) {
            return 0d;
        }
        if (coreConflicts(item, material)) {
            return 0d;
        }
        double score = TextUtil.similarity(itemName, drawName);
        if (drawName.contains(itemName) || itemName.contains(drawName)) {
            score = Math.max(score, 0.72);
        }
        return score;
    }

    /** 芯数冲突（清单 24 芯 vs 图纸 48 芯）不能算同一材料 */
    private boolean coreConflicts(BoqItem item, DrawingMaterial material) {
        Integer itemCore = coreOf(item.getItemName() + " " + TextUtil.nullToEmpty(item.getFeatureDesc()));
        Integer drawCore = coreOf(material.name() + " " + material.spec());
        return itemCore != null && drawCore != null && !itemCore.equals(drawCore);
    }

    /** 取材料的芯数：先按「24芯」，再按光缆型号里的 24B1 */
    private Integer coreOf(String text) {
        String normalized = TextUtil.normalize(text);
        Matcher matcher = CORE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        Matcher model = CORE_MODEL_PATTERN.matcher(normalized);
        return model.find() ? Integer.parseInt(model.group(1)) : null;
    }

    // ------------------------------------------------------------------
    // 解析结果读取与通用工具
    // ------------------------------------------------------------------

    private Map<String, Object> readParseResult(ProjectFile file) {
        String json = file.getParseResult();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.toMap(json);
        } catch (RuntimeException e) {
            log.warn("图纸解析结果不是合法 JSON，跳过：fileId={} name={}", file.getId(), file.getFileName());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void collectMaterials(Object summary, String fileName,
                                  Map<String, DrawingMaterial> materials) {
        if (!(summary instanceof List<?> rows)) {
            return;
        }
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)) {
                continue;
            }
            String name = text(row.get("name"));
            String spec = text(row.get("spec"));
            String unit = text(row.get("unit"));
            BigDecimal quantity = decimal(row.get("quantity"));
            if (name.isBlank() || quantity == null || quantity.signum() <= 0) {
                continue;
            }
            // 同物异名（新立光交 / 新建光缆交接箱）按规范名合并；单位不同（个 vs 头）不能相加
            String key = TextUtil.canonicalName(name) + "|" + TextUtil.normalize(spec)
                    + "|" + TextUtil.normalizeUnit(unit);
            DrawingMaterial exist = materials.get(key);
            if (exist == null) {
                List<String> files = new ArrayList<>();
                files.add(fileName);
                materials.put(key, new DrawingMaterial(name, spec, unit, quantity, files));
            } else {
                if (!exist.files().contains(fileName)) {
                    exist.files().add(fileName);
                }
                // 合并后展示更完整的写法（字多的一般是全称）
                String mergedName = name.length() > exist.name().length() ? name : exist.name();
                materials.put(key, new DrawingMaterial(mergedName, exist.spec(), exist.unit(),
                        exist.quantity().add(quantity), exist.files()));
            }
        }
    }

    private boolean unitConvertible(String itemUnit, String drawingUnit) {
        String a = TextUtil.normalizeUnit(itemUnit);
        String b = TextUtil.normalizeUnit(drawingUnit);
        if (a.isBlank() || b.isBlank() || a.equals(b)) {
            return true;
        }
        return factorOf(a, b) != null;
    }

    /** 清单数量换算到图纸单位的系数；计件单位互通（套↔个），长度/面积/体积各自换算 */
    private BigDecimal factorOf(String itemUnit, String drawingUnit) {
        String a = TextUtil.normalizeUnit(itemUnit);
        String b = TextUtil.normalizeUnit(drawingUnit);
        // 有一侧没写单位时按 1:1 比：清单与图纸都缺单位的情况多半是同一口径，先给结论让人复核
        if (a.isBlank() || b.isBlank() || a.equals(b)) {
            return BigDecimal.ONE;
        }
        if (COUNT_UNITS.contains(a) && COUNT_UNITS.contains(b)) {
            return BigDecimal.ONE;
        }
        return TextUtil.unitFactor(a, b);
    }

    /** 图纸相对清单的偏差率：(图纸 - 清单) / 清单 */
    private BigDecimal diffRate(BigDecimal drawing, BigDecimal boq) {
        if (boq == null || boq.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return drawing.subtract(boq).divide(boq, 4, RoundingMode.HALF_UP);
    }

    private Issue buildIssue(Long projectId, BoqItem item, int level, String ruleCode,
                             String title, String detail, List<String> files) {
        Issue issue = new Issue();
        issue.setProjectId(projectId);
        issue.setBoqItemId(item == null ? null : item.getId());
        issue.setIssueType(Dict.ISSUE_DRAWING);
        issue.setLevel(level);
        issue.setRuleCode(ruleCode);
        issue.setRuleId(null);
        issue.setRefType("DRAWING");
        issue.setRefValue(TextUtil.truncate(joinFiles(files), 200));
        issue.setTitle(TextUtil.truncate(title, 200));
        issue.setDetail(TextUtil.truncate(detail, 4000));
        issue.setReviewStatus(Dict.REVIEW_PENDING);
        if (item != null && item.getRowNo() != null) {
            issue.setRefType("BOQ_ROW");
            issue.setRefValue(item.getRowNo() + " " + TextUtil.truncate(joinFiles(files), 150));
        }
        return issue;
    }

    private String joinFiles(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "-";
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(files));
        String joined = String.join("、", distinct.stream().limit(3).toList());
        return distinct.size() > 3 ? joined + " 等 " + distinct.size() + " 张" : joined;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
