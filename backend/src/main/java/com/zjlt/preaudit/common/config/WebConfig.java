package com.zjlt.preaudit.common.config;

import com.zjlt.preaudit.common.security.AuthInterceptor;
import com.zjlt.preaudit.module.system.audit.AuditInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web 配置：跨域（本地开发默认放行，容器环境由 ALLOWED_ORIGINS 指定）+ 认证拦截 + 审计日志（G3 安全基线）
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuditInterceptor auditInterceptor;

    /** 允许的跨域来源（逗号分隔，支持通配）；同域反代部署时可留空 */
    @Value("${preaudit.web.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!StringUtils.hasText(allowedOrigins)) {
            return;
        }
        String[] patterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (patterns.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/health",
                        "/error",
                        "/doc.html",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        "/favicon.ico");
        // 审计在认证之后注册，afterCompletion 先于认证清理用户上下文执行
        registry.addInterceptor(auditInterceptor).addPathPatterns("/**");
    }
}
