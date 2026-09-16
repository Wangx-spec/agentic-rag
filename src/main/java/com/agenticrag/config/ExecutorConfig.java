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
        // daemon 线程：非 web 模式（如 eval 批跑）主线程返回后 JVM 可正常退出，
        // 否则空闲的池工作线程会一直阻塞 JVM 退出；web 模式由 Tomcat 持有 JVM，关闭行为不变
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
    
}
