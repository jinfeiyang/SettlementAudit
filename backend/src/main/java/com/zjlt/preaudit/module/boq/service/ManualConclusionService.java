package com.zjlt.preaudit.module.boq.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.module.boq.entity.ManualConclusion;
import com.zjlt.preaudit.module.boq.mapper.ManualConclusionMapper;
import com.zjlt.preaudit.module.project.service.ProjectFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 人工结论统一台账：登记、查看、撤销四类人工判断。 */
@Service
@RequiredArgsConstructor
public class ManualConclusionService {

    public static final String DRAWING_EDIT = "DRAWING_EDIT";
    public static final String MERGE = "MERGE";
    public static final String MATCH = "MATCH";
    public static final String QUANTITY_FIX = "QUANTITY_FIX";

    private final ManualConclusionMapper mapper;
    private final BoqService boqService;
    private final ProjectFileService projectFileService;

    public List<ManualConclusion> list(Long projectId) {
        backfillIfEmpty(projectId);
        return mapper.selectList(Wrappers.<ManualConclusion>lambdaQuery()
                .eq(ManualConclusion::getProjectId, projectId)
                .orderByDesc(ManualConclusion::getCreateTime));
    }

    /** 台账为空时，把系统里已有的四类人工结论补登一次，兼容历史项目。 */
    @Transactional
    public void backfillIfEmpty(Long projectId) {
        Long count = mapper.selectCount(Wrappers.<ManualConclusion>lambdaQuery()
                .eq(ManualConclusion::getProjectId, projectId));
        if (count != null && count > 0) {
            return;
        }
        for (var edit : boqService.listDrawingEdits(projectId)) {
            String after = edit.name() + "/" + edit.spec() + "/" + edit.unit() + "/" + edit.quantity();
            record(projectId, DRAWING_EDIT, edit.materialKey(), edit.materialKey(), null, null,
                    "图纸材料 " + edit.materialKey(), "历史结论：改为 " + after,
                    "原始解析值", after);
        }
        Map<String, String> groupNames = new LinkedHashMap<>();
        Map<String, List<String>> groupMembers = new LinkedHashMap<>();
        for (var merge : boqService.listDrawingMerges(projectId)) {
            groupNames.put(merge.groupKey(), merge.displayName());
            groupMembers.computeIfAbsent(merge.groupKey(), key -> new ArrayList<>()).add(merge.memberKey());
        }
        for (Map.Entry<String, List<String>> group : groupMembers.entrySet()) {
            String members = String.join(",", group.getValue());
            record(projectId, MERGE, group.getKey(), group.getKey(), null, null,
                    "图纸材料合并组 " + members, "历史结论：合并为 " + groupNames.get(group.getKey()),
                    "原始独立材料", groupNames.get(group.getKey()) + " ← " + members);
        }
        for (var match : boqService.listDrawingMatches(projectId)) {
            String key = "MATCH:" + match.boqItemId();
            record(projectId, MATCH, key, String.valueOf(match.boqItemId()), null, null,
                    "工程量清单行 " + match.boqItemId(),
                    "历史结论：" + match.manualType() + " / " + match.drawingKey(),
                    "自动匹配", match.manualType() + " / " + match.drawingKey());
        }
        for (Map<String, Object> fix : projectFileService.listQuantityFixes(projectId)) {
            String fileId = String.valueOf(fix.get("fileId"));
            String materialKey = String.valueOf(fix.get("materialKey"));
            String partKey = String.valueOf(fix.get("partKey"));
            String value = String.valueOf(fix.get("value"));
            record(projectId, QUANTITY_FIX, fileId + "|" + materialKey + "|" + partKey,
                    fileId, materialKey, partKey,
                    "图纸文件 " + fileId + " / 材料 " + materialKey,
                    "历史结论：数量分项 " + partKey + " 改为 " + value,
                    "AI 原值", value);
        }
    }

    /**
     * 登记一次人工结论。同一业务键再次保存时，旧记录自动标记为已撤销，保留历史。
     */
    @Transactional
    public void record(Long projectId, String type, String bizKey,
                       String ref1, String ref2, String ref3,
                       String sourceDesc, String summary,
                       String beforeValue, String afterValue) {
        LocalDateTime now = LocalDateTime.now();
        String operator = UserContext.username();
        mapper.update(new ManualConclusion(), Wrappers.<ManualConclusion>lambdaUpdate()
                .eq(ManualConclusion::getProjectId, projectId)
                .eq(ManualConclusion::getType, type)
                .eq(ManualConclusion::getBizKey, bizKey)
                .eq(ManualConclusion::getStatus, "ACTIVE")
                .set(ManualConclusion::getStatus, "REVOKED")
                .set(ManualConclusion::getRevokeBy, operator)
                .set(ManualConclusion::getRevokeTime, now));

        ManualConclusion item = new ManualConclusion();
        item.setProjectId(projectId);
        item.setType(type);
        item.setBizKey(bizKey);
        item.setRef1(ref1);
        item.setRef2(ref2);
        item.setRef3(ref3);
        item.setSourceDesc(sourceDesc);
        item.setSummary(summary);
        item.setBeforeValue(beforeValue);
        item.setAfterValue(afterValue);
        item.setUserId(UserContext.userId());
        item.setUsername(operator);
        item.setStatus("ACTIVE");
        item.setCreateTime(now);
        item.setUpdateTime(now);
        mapper.insert(item);
    }

    /** 原页面直接撤销时同步把台账里的当前结论标记为已撤销。 */
    @Transactional
    public void markRevoked(Long projectId, String type, String bizKey) {
        mapper.update(new ManualConclusion(), Wrappers.<ManualConclusion>lambdaUpdate()
                .eq(ManualConclusion::getProjectId, projectId)
                .eq(ManualConclusion::getType, type)
                .eq(ManualConclusion::getBizKey, bizKey)
                .eq(ManualConclusion::getStatus, "ACTIVE")
                .set(ManualConclusion::getStatus, "REVOKED")
                .set(ManualConclusion::getRevokeBy, UserContext.username())
                .set(ManualConclusion::getRevokeTime, LocalDateTime.now()));
    }

    /** 从统一台账撤销：委托给原有删除逻辑，再把台账记录标记为已撤销。 */
    @Transactional
    public void revoke(Long id) {
        ManualConclusion item = mapper.selectById(id);
        if (item == null) {
            throw new BizException("人工结论不存在");
        }
        if ("REVOKED".equals(item.getStatus())) {
            return;
        }
        switch (item.getType()) {
            case DRAWING_EDIT -> boqService.deleteDrawingEdit(item.getProjectId(), item.getRef1());
            case MERGE -> boqService.deleteDrawingMerges(item.getProjectId(), item.getRef1());
            case MATCH -> boqService.deleteDrawingMatch(item.getProjectId(), Long.valueOf(item.getRef1()));
            case QUANTITY_FIX -> projectFileService.deleteQuantityFix(
                    Long.valueOf(item.getRef1()), item.getRef2(), item.getRef3());
            default -> throw new BizException("未知人工结论类型：" + item.getType());
        }
        LambdaUpdateWrapper<ManualConclusion> wrapper = Wrappers.<ManualConclusion>lambdaUpdate()
                .eq(ManualConclusion::getId, id)
                .set(ManualConclusion::getStatus, "REVOKED")
                .set(ManualConclusion::getRevokeBy, UserContext.username())
                .set(ManualConclusion::getRevokeTime, LocalDateTime.now());
        mapper.update(new ManualConclusion(), wrapper);
    }
}
