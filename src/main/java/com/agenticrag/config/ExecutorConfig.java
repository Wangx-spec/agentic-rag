package com.agenticrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置：
 * - multiAgentExecutor：多 Agent 子任务专用，饱和时 CallerRuns 背压；
 * - memoryExecutor：记忆写回后台任务专用（M9 摘要压缩等），饱和时记日志丢弃（fail-open），
 *   绝不阻塞聊天请求线程。
 */
@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

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

    /**
     * 记忆写回专用线程池（M9）：跑摘要压缩等低优先级后台任务。
     * 队列饱和时丢弃并记警告（写回是尽力而为的增强链路，丢一轮不影响正确性），
     * 与 CallerRuns 的区别：绝不反向阻塞请求线程。
     */
    @Bean("memoryExecutor")
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("memory-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler((r, pool) ->
                log.warn("记忆写回任务队列已满，本次任务按 fail-open 丢弃"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
