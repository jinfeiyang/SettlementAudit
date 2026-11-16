package com.zjlt.preaudit.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（Knife4j）：启动后访问 /api/doc.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("通信工程造价结算 AI 预审分析工具 - 业务服务 API")
                .description("一期模块化单体；AI/解析能力见独立 ai-service（8000）")
                .version("0.1.0"));
    }
}
