package com.agenticrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 多 Agent 链路专用线程池：与聊天链路使用的 ForkJoinPool.commonPool() 隔离，
 * 避免并行子任务批量挤占普通聊天线程；饱和时 CallerRuns 提供天然背压，避免任务堆积
 */
@Configuration
public class ExecutorConfig {

    @Bean("multiAgentExecutor")
    public ThreadPoolTaskExecutor multiAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("multiAgent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
    
}
