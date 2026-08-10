package com.llmwiki;

import com.llmwiki.config.LlmWikiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LLM Wiki 企业服务端入口，负责装配 Web、事务、缓存、后台任务和 MCP 能力。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LlmWikiProperties.class)
public class LlmWikiApplication {

    /**
     * 启动服务端进程。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LlmWikiApplication.class, args);
    }
}

