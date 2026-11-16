package com.zjlt.preaudit.module.project.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.module.project.dto.ProjectDtos;
import com.zjlt.preaudit.module.project.service.ProjectFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Tag(name = "项目资料")
@RestController
@RequestMapping
@RequiredArgsConstructor
public class ProjectFileController {

    private final ProjectFileService fileService;

    @Operation(summary = "上传资料（清单 xlsx / 图纸 dxf、dwg）")
    @PostMapping("/projects/{projectId}/files")
    public R<ProjectDtos.FileVO> upload(@PathVariable Long projectId, @RequestParam("file") MultipartFile file) {
        return R.ok(fileService.upload(projectId, file));
    }

    @Operation(summary = "资料列表")
    @GetMapping("/projects/{projectId}/files")
    public R<List<ProjectDtos.FileVO>> list(@PathVariable Long projectId) {
        return R.ok(fileService.list(projectId));
    }

    @Operation(summary = "触发解析（异步）")
    @PostMapping("/files/{id}/parse")
    public R<Void> parse(@PathVariable Long id) {
        fileService.requireFile(id);
        fileService.parseAsync(id);
        return R.ok();
    }

    @Operation(summary = "同步解析（调试/小文件用）")
    @PostMapping("/files/{id}/parse-sync")
    public R<ProjectDtos.FileVO> parseSync(@PathVariable Long id) {
        return R.ok(fileService.parseNow(id));
    }

    @Operation(summary = "删除资料")
    @DeleteMapping("/files/{id}")
    public R<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return R.ok();
    }

    @Operation(summary = "查看解析结果（图纸量算/清单统计）")
    @GetMapping("/files/{id}/result")
    public R<java.util.Map<String, Object>> result(@PathVariable Long id) {
        return R.ok(JsonUtil.toMap(fileService.requireFile(id).getParseResult()));
    }

    @Operation(summary = "图纸预览（dxf 交互数据 / svg 只读快照）")
    @GetMapping("/files/{id}/drawing")
    public ResponseEntity<byte[]> drawing(@PathVariable Long id,
                                         @RequestParam(defaultValue = "dxf") String format) {
        var drawing = fileService.drawingPreview(id, format);
        MediaType contentType = "svg".equalsIgnoreCase(format)
                ? MediaType.valueOf("image/svg+xml")
                : MediaType.valueOf("application/dxf");
        return ResponseEntity.ok()
                .contentType(contentType)
                .header("X-Drawing-Cache", drawing.cacheHit() == null ? "" : drawing.cacheHit())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .body(drawing.data());
    }

    @Operation(summary = "下载资料原件")
    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Path path = fileService.downloadPath(id);
        if (!Files.exists(path)) {
            throw new BizException(404, "文件已丢失，请重新上传");
        }
        String name = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
