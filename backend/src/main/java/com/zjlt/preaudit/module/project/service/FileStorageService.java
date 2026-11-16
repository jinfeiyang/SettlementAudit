package com.zjlt.preaudit.module.project.service;

import com.zjlt.preaudit.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * 资料原件存储（容器云阶段替换为 PVC/MinIO，接口不变）
 */
@Slf4j
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${preaudit.storage.root:./data/uploads}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存储目录: " + this.root, e);
        }
    }

    /** 保存文件，返回相对路径（projectId/uuid.ext） */
    public String save(Long projectId, String originalFilename, InputStream in) {
        String ext = extension(originalFilename);
        String relative = projectId + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法的文件路径");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return relative;
        } catch (IOException e) {
            throw new BizException("文件保存失败：" + e.getMessage());
        }
    }

    public Path resolve(String relative) {
        Path target = root.resolve(relative == null ? "" : relative).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法的文件路径");
        }
        return target;
    }

    public void deleteQuietly(String relative) {
        if (relative == null || relative.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (Exception e) {
            log.warn("删除文件失败 {}: {}", relative, e.getMessage());
        }
    }

    private String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
