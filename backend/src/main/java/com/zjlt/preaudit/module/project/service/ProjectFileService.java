package com.zjlt.preaudit.module.project.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.ai.client.AiServiceClient;
import com.zjlt.preaudit.module.boq.dto.BoqDtos;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingEdit;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingMerge;
import com.zjlt.preaudit.module.boq.mapper.BoqDrawingEditMapper;
import com.zjlt.preaudit.module.boq.mapper.BoqDrawingMergeMapper;
import com.zjlt.preaudit.module.boq.service.BoqService;
import com.zjlt.preaudit.module.project.dto.ProjectDtos;
import com.zjlt.preaudit.module.project.entity.ProjectFile;
import com.zjlt.preaudit.module.project.mapper.ProjectFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 项目资料管理（A5 后端）：上传、解析调度、状态跟踪
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFileService {

    private final ProjectFileMapper fileMapper;
    private final ProjectService projectService;
    private final FileStorageService storage;
    private final BoqService boqService;
    private final BoqDrawingEditMapper drawingEditMapper;
    private final BoqDrawingMergeMapper drawingMergeMapper;
    private final AiServiceClient aiClient;

    @Transactional
    public ProjectDtos.FileVO upload(Long projectId, MultipartFile file) {
        projectService.requireProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "未命名文件";
        String fileType = detectType(original);
        String relative;
        try (InputStream in = file.getInputStream()) {
            relative = storage.save(projectId, original, in);
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
        ProjectFile rec = new ProjectFile();
        rec.setProjectId(projectId);
        rec.setFileType(fileType);
        rec.setFileName(original);
        rec.setStoragePath(relative);
        rec.setFileSize(file.getSize());
        rec.setParseStatus(Dict.PARSE_PENDING);
        fileMapper.insert(rec);
        return toVO(rec);
    }

    public List<ProjectDtos.FileVO> list(Long projectId) {
        projectService.requireProject(projectId);
        return fileMapper.selectList(Wrappers.<ProjectFile>lambdaQuery()
                        .eq(ProjectFile::getProjectId, projectId)
                        .orderByDesc(ProjectFile::getId))
                .stream().map(this::toVO).toList();
    }

    @Transactional
    public void delete(Long fileId) {
        ProjectFile file = requireFile(fileId);
        fileMapper.deleteById(fileId);
        storage.deleteQuietly(file.getStoragePath());
    }

    /** 异步解析（G2）：上传后由前端轮询文件解析状态 */
    @Async("checkExecutor")
    public void parseAsync(Long fileId) {
        try {
            parse(fileId);
        } catch (Exception e) {
            log.error("文件解析失败 fileId={}", fileId, e);
            markFailed(fileId, "解析异常：" + e.getMessage());
        }
    }

    public ProjectDtos.FileVO parseNow(Long fileId) {
        parse(fileId);
        return toVO(requireFile(fileId));
    }

    private void parse(Long fileId) {
        ProjectFile file = requireFile(fileId);
        updateStatus(file, Dict.PARSE_PARSING, null, "解析中…", null);
        switch (file.getFileType()) {
            case Dict.FILE_BOQ -> parseBoq(file);
            case Dict.FILE_DXF, Dict.FILE_DWG -> parseDrawing(file);
            default -> markFailed(fileId, "该文件类型暂不支持自动解析（一期支持 xlsx/xls 清单与 DXF/DWG 图纸）");
        }
    }

    private void parseBoq(ProjectFile file) {
        Path path = storage.resolve(file.getStoragePath());
        try (InputStream in = Files.newInputStream(path)) {
            BoqDtos.ImportResult result = boqService.importFromFile(file.getProjectId(), file.getId(),
                    in, file.getFileName());
            if (result.imported() == 0) {
                updateStatus(file, Dict.PARSE_FAILED, result.parseRate(), result.message(),
                        JsonUtil.toJson(result));
                return;
            }
            updateStatus(file, Dict.PARSE_SUCCESS, result.parseRate(), result.message(), JsonUtil.toJson(result));
        } catch (IOException e) {
            markFailed(file.getId(), "清单读取失败：" + e.getMessage());
        } catch (Exception e) {
            log.warn("清单解析失败 fileId={}", file.getId(), e);
            markFailed(file.getId(), TextUtil.truncate(e.getMessage(), 900));
        }
    }

    private void parseDrawing(ProjectFile file) {
        // 重新解析以 AI 结果为准：整体替换 parse_result，旧的人工修正不再带入，
        // 图纸清单上的「已改」标记随之清空，避免旧修正盖在新解析值上造成错值。
        Path path = storage.resolve(file.getStoragePath());
        Optional<Map<String, Object>> result = aiClient.parseDrawing(path, file.getFileName());
        if (result.isEmpty()) {
            markFailed(file.getId(),
                    "AI/解析服务不可用（需启动 ai-service 并安装 ezdxf；DWG 还需 ODA File Converter）。"
                            + "图纸解析失败不影响清单核查主链路。");
            return;
        }
        Map<String, Object> data = result.get();
        String status = String.valueOf(data.getOrDefault("status", ""));
        if ("FAILED".equalsIgnoreCase(status)) {
            markFailed(file.getId(), String.valueOf(data.getOrDefault("message", "图纸解析失败")));
            return;
        }
        String message = String.valueOf(data.getOrDefault("summary", "图纸解析完成"));
        updateStatus(file, Dict.PARSE_SUCCESS, null, message, JsonUtil.toJson(data));
    }

    /**
     * 保存量算结果里人工编辑后的材料表，并让 AI 服务重新生成全部派生数据。
     * 与「重新解析」一致：归纳统计、光缆长度、解析摘要整体替换，旧的数量分项修正不再带入。
     */
    @Transactional
    public Map<String, Object> saveMaterialTables(Long fileId, List<Map<String, Object>> tables) {
        ProjectFile file = requireFile(fileId);
        if (!Dict.FILE_DXF.equals(file.getFileType()) && !Dict.FILE_DWG.equals(file.getFileType())) {
            throw new BizException("仅图纸支持编辑材料表：" + file.getFileName());
        }
        if (tables == null) {
            throw new BizException("材料表不能为空");
        }
        Path path = storage.resolve(file.getStoragePath());
        Optional<Map<String, Object>> result = aiClient.parseDrawingEdited(path, file.getFileName(), tables);
        if (result.isEmpty()) {
            throw new BizException(503, "AI/解析服务不可用，无法重算材料表");
        }
        Map<String, Object> data = result.get();
        if ("FAILED".equalsIgnoreCase(String.valueOf(data.getOrDefault("status", "")))) {
            throw new BizException(422, String.valueOf(data.getOrDefault("message", "材料表重算失败")));
        }
        String message = String.valueOf(data.getOrDefault("summary", "材料表已更新"));
        // 明细表已经是最新口径，图纸清单里的「人工编辑/合并组」会覆盖或改名新解析值，
        // 造成“明细改了但图纸清单没变”，这里按重新解析语义一并清掉。
        drawingEditMapper.delete(Wrappers.<BoqDrawingEdit>lambdaQuery()
                .eq(BoqDrawingEdit::getProjectId, file.getProjectId()));
        drawingMergeMapper.delete(Wrappers.<BoqDrawingMerge>lambdaQuery()
                .eq(BoqDrawingMerge::getProjectId, file.getProjectId()));
        updateStatus(file, Dict.PARSE_SUCCESS, null, message, JsonUtil.toJson(data));
        return data;
    }

    /**
     * 保存人工修正的数量分项：材料标识 → 分项键 → 数值，写回 parse_result 的独立字段，
     * 不动 AI 原始解析数据；重新解析时由 parseDrawing 原样带过去。
     */
    public void saveQuantityFix(Long fileId, String materialKey, String partKey, BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new BizException("修正数量必须是非负数");
        }
        ProjectFile file = requireFile(fileId);
        Map<String, Object> data = new LinkedHashMap<>(JsonUtil.toMap(file.getParseResult()));
        Object rawFixes = data.get("manualQuantityFixes");
        Map<String, Object> fixes = rawFixes instanceof Map
                ? new LinkedHashMap<>(castMap(rawFixes)) : new LinkedHashMap<>();
        Object rawParts = fixes.get(materialKey);
        Map<String, Object> parts = rawParts instanceof Map
                ? new LinkedHashMap<>(castMap(rawParts)) : new LinkedHashMap<>();
        parts.put(partKey, value);
        fixes.put(materialKey, parts);
        data.put("manualQuantityFixes", fixes);
        updateStatus(file, file.getParseStatus(), file.getParseRate(), file.getParseMessage(), JsonUtil.toJson(data));
    }

    /** 删除单条人工修正（恢复 AI 原始解析值） */
    public void deleteQuantityFix(Long fileId, String materialKey, String partKey) {
        ProjectFile file = requireFile(fileId);
        Map<String, Object> data = new LinkedHashMap<>(JsonUtil.toMap(file.getParseResult()));
        Object rawFixes = data.get("manualQuantityFixes");
        if (!(rawFixes instanceof Map)) {
            return;
        }
        Map<String, Object> fixes = new LinkedHashMap<>(castMap(rawFixes));
        Object rawParts = fixes.get(materialKey);
        if (rawParts instanceof Map) {
            Map<String, Object> parts = new LinkedHashMap<>(castMap(rawParts));
            parts.remove(partKey);
            if (parts.isEmpty()) {
                fixes.remove(materialKey);
            } else {
                fixes.put(materialKey, parts);
            }
        }
        if (fixes.isEmpty()) {
            data.remove("manualQuantityFixes");
        } else {
            data.put("manualQuantityFixes", fixes);
        }
        updateStatus(file, file.getParseStatus(), file.getParseRate(), file.getParseMessage(), JsonUtil.toJson(data));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void updateStatus(ProjectFile file, String status, BigDecimal rate, String message, String resultJson) {
        file.setParseStatus(status);
        file.setParseRate(rate);
        file.setParseMessage(TextUtil.truncate(message, 1000));
        file.setParseResult(resultJson);
        fileMapper.updateById(file);
    }

    private void markFailed(Long fileId, String message) {
        ProjectFile file = fileMapper.selectById(fileId);
        if (file == null) {
            return;
        }
        updateStatus(file, Dict.PARSE_FAILED, null, message, null);
    }

    public ProjectFile requireFile(Long fileId) {
        ProjectFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BizException(404, "文件不存在：" + fileId);
        }
        return file;
    }

    public Path downloadPath(Long fileId) {
        ProjectFile file = requireFile(fileId);
        return storage.resolve(file.getStoragePath());
    }

    /**
     * 图纸预览：dxf 返回转换后的 DXF（前端交互渲染、可点选图元），svg 返回服务端渲染快照。
     * 转换产物由 AI 服务按内容哈希缓存，同一图纸只转换一次。
     */
    public AiServiceClient.DrawingData drawingPreview(Long fileId, String format) {
        ProjectFile file = requireFile(fileId);
        if (!"DWG".equals(file.getFileType()) && !"DXF".equals(file.getFileType())) {
            throw new BizException("仅支持图纸预览：" + file.getFileName());
        }
        Path path = storage.resolve(file.getStoragePath());
        if (!Files.exists(path)) {
            throw new BizException(404, "图纸文件已丢失，请重新上传");
        }
        String fmt = "svg".equalsIgnoreCase(format) ? "svg" : "dxf";
        return aiClient.renderDrawing(path, file.getFileName(), fmt, 420, 297)
                .orElseThrow(() -> new BizException(503,
                        "AI/解析服务不可用，无法出图（需启动 ai-service 并安装 ezdxf；DWG 还需 ODA File Converter）"));
    }

    private String detectType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) {
            return Dict.FILE_BOQ;
        }
        if (lower.endsWith(".dxf")) {
            return Dict.FILE_DXF;
        }
        if (lower.endsWith(".dwg")) {
            return Dict.FILE_DWG;
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf")) {
            return Dict.FILE_OTHER;
        }
        return Dict.FILE_OTHER;
    }

    private ProjectDtos.FileVO toVO(ProjectFile f) {
        return new ProjectDtos.FileVO(f.getId(), f.getProjectId(), f.getFileType(), f.getFileName(),
                f.getFileSize(), f.getParseStatus(), Dict.parseStatus(f.getParseStatus()), f.getParseRate(),
                f.getParseMessage(), f.getCreateTime());
    }
}
