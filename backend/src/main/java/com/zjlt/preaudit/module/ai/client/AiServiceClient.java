package com.zjlt.preaudit.module.ai.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.JsonUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI/解析服务客户端（FastAPI，独立进程）。
 * 设计原则：AI 不可用时主链路仍可跑通——解析/成文提供确定性降级。
 */
@Slf4j
@Component
public class AiServiceClient {

    private final RestClient restClient;
    private final boolean enabled;

    public AiServiceClient(@Value("${preaudit.ai-service.base-url:http://localhost:8000}") String baseUrl,
                           @Value("${preaudit.ai-service.connect-timeout-ms:5000}") int connectTimeoutMs,
                           @Value("${preaudit.ai-service.read-timeout-ms:60000}") int readTimeoutMs,
                           @Value("${preaudit.ai-service.enabled:true}") boolean enabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.enabled = enabled;
    }

    /** DXF/DWG 矢量解析与量算 */
    public Optional<Map<String, Object>> parseDrawing(Path file, String filename) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            Map<String, Object> result = restClient.post()
                    .uri("/parse/dxf")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return Optional.ofNullable(result);
        } catch (IOException e) {
            log.warn("读取图纸失败: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AI 服务图纸解析不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按人工编辑后的材料表重新解析：AI 服务重新计算归纳统计、光缆长度与解析摘要。
     */
    public Optional<Map<String, Object>> parseDrawingEdited(Path file, String filename,
                                                             List<Map<String, Object>> materials) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            form.add("materials", JsonUtil.toJson(materials));
            Map<String, Object> result = restClient.post()
                    .uri("/parse/dxf-edited")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return Optional.ofNullable(result);
        } catch (IOException e) {
            log.warn("读取图纸失败: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AI 服务材料表重算不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 图纸出图：format=dxf 返回转换后的 DXF（前端交互渲染），format=svg 返回服务端渲染快照。
     * 转换失败（未装 ODA、图纸版本不兼容、加密）由 AI 服务返回 422 + 原因，此处转成可读异常。
     */
    public Optional<DrawingData> renderDrawing(Path file, String filename, String format,
                                               int widthMm, int heightMm) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            ResponseEntity<byte[]> resp = restClient.post()
                    .uri(builder -> builder.path("/render/drawing")
                            .queryParam("format", format)
                            .queryParam("width_mm", widthMm)
                            .queryParam("height_mm", heightMm)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toEntity(byte[].class);
            MediaType contentType = resp.getHeaders().getContentType();
            String cache = resp.getHeaders().getFirst("X-Cache");
            return Optional.of(new DrawingData(resp.getBody(),
                    contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType.toString(),
                    cache));
        } catch (IOException e) {
            log.warn("读取图纸失败: {}", e.getMessage());
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // 4xx/5xx：把 AI 服务给出的具体原因带回前端（例如未安装 ODA）
            throw new BizException(422, e.getResponseBodyAsString().isBlank()
                    ? "图纸转换失败" : TextUtil.truncate(e.getResponseBodyAsString(), 800));
        } catch (Exception e) {
            log.warn("AI 服务出图不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 出图结果：字节内容 + MIME + AI 侧缓存命中标记（hit/miss） */
    public record DrawingData(byte[] data, String contentType, String cacheHit) {
    }

    /** LLM 网关（元景/vLLM）：失败返回 empty，由调用方降级为模板文案 */
    public Optional<ChatResp> chat(String taskType, String prompt, Map<String, Object> context) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            ChatResp resp = restClient.post()
                    .uri("/llm/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("task_type", taskType, "prompt", prompt,
                            "context", context == null ? Map.of() : context))
                    .retrieve()
                    .body(ChatResp.class);
            if (resp == null || resp.content() == null || resp.content().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(resp);
        } catch (Exception e) {
            log.warn("LLM 网关不可用（{}）：{}", taskType, e.getMessage());
            return Optional.empty();
        }
    }

    public record ChatResp(String content, String model,
                           @JsonProperty("tokens_used") Integer tokensUsed) {
    }

    public static Map<String, Object> asMap(Object value) {
        return JsonUtil.toMap(JsonUtil.toJson(value));
    }
}
