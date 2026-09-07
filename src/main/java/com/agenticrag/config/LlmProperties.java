package com.agenticrag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置（OpenAI 兼容接口）
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** OpenAI 兼容 base-url，如 https://api.siliconflow.cn/v1 */
    private String baseUrl;

    /** API Key，建议用环境变量 LLM_API_KEY 注入，不要提交到仓库 */
    private String apiKey;

    /** 模型名，如 Qwen/Qwen2.5-72B-Instruct */
    private String chatModel;

    private String embeddingModel;

    /** 请求超时（秒） */
    private int timeoutSeconds = 60;

    /** Agent 循环最大轮次（M3 启用） */
    private int maxAgentRounds = 5;

    /** 会话记忆保留的最近轮数 */
    private int memoryRounds = 10;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
