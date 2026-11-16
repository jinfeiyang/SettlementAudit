package com.zjlt.preaudit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 通信工程造价结算 AI 预审分析工具 —— 业务服务入口
 * 一期为模块化单体，二期按服务边界拆分。
 */
@EnableAsync
@MapperScan("com.zjlt.preaudit.module.**.mapper")
@SpringBootApplication
public class CostPreauditApplication {

    public static void main(String[] args) {
        SpringApplication.run(CostPreauditApplication.class, args);
    }
}