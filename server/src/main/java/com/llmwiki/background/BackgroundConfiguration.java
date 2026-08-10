package com.llmwiki.background;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置后台任务执行资源。虚拟线程适合等待 Python/LLM HTTP 的 I/O 型任务，队列并发仍由数据库租约限制。
 */
@Configuration
public class BackgroundConfiguration {

    /**
     * 创建可在应用关闭时统一回收的虚拟线程执行器。
     *
     * @return 后台摄取执行器
     */
    @Bean(destroyMethod = "close")
    public ExecutorService ingestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

