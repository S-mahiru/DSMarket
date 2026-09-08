package com.dsmarket.modules.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AI SSE 生成线程池：controller 提交生成任务后立即返回 SseEmitter，事件由此池线程写出。
 * 独立有界池，避免长时间模型/工具往返占满 Tomcat 工作线程。
 */
@Configuration
public class AiAsyncConfig {

    @Bean("aiChatExecutor")
    public Executor aiChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
