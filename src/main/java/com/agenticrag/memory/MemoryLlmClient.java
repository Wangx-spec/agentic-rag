package com.agenticrag.memory;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M9 记忆专用小模型客户端：F2 滑动摘要与 F7 批量提取共用（技术决策 8）。
 * <p>
 * 配置选择策略（N2）：rag.memory.llm 任一字段非空视为专用配置，
 * 非空字段逐项覆盖主 LLM，其余字段沿用主配置——支持「只换模型不换网关」的部署形态；
 * 全空则完全回退主 LLM。选择逻辑收口在 effectiveProperties()，
 * 每次 chat 直接以生效配置实例化 LlmClient（与 IntentClassifier.callLlm 同模式，
 * 低频后台任务量级下无需连接复用）。本层不吞异常：调用方按 N1 静默降级。
 */
@Slf4j
@Component
public class MemoryLlmClient {

    private final MemoryProperties memoryProperties;
    private final LlmProperties mainProperties;

    public MemoryLlmClient(MemoryProperties memoryProperties, LlmProperties mainProperties) {
        this.memoryProperties = memoryProperties;
        this.mainProperties = mainProperties;
    }

        /**
     * 同步调用记忆小模型。
     *
     * @throws LlmClient.LlmException 配置缺失或调用失败时原样抛出，由调用方按 N1 降级
     */
    public String chat(List<ChatMessage> messages) {
        LlmProperties effective = effectiveProperties();
        log.debug("记忆小模型调用: dedicated={}, model={}, timeout={}s",
                memoryProperties.getLlm().hasDedicated(),
                effective.getChatModel(),
                effective.getTimeoutSeconds());
        return new LlmClient(effective).chat(messages);
    }

    /**
     * 生效配置合成：以主 LLM 为底，rag.memory.llm 非空字段逐项覆盖。
     * 包级可见供单测覆盖（对齐 IntentClassifier.parseIntent 的测试约定）。
     */
    LlmProperties effectiveProperties() {
        LlmProperties merged = new LlmProperties();
        merged.setBaseUrl(mainProperties.getBaseUrl());
        merged.setApiKey(mainProperties.getApiKey());
        merged.setChatModel(mainProperties.getChatModel());
        merged.setEmbeddingModel(mainProperties.getEmbeddingModel());
        merged.setTimeoutSeconds(mainProperties.getTimeoutSeconds());
        merged.setMaxAgentRounds(mainProperties.getMaxAgentRounds());
        merged.setMemoryRounds(mainProperties.getMemoryRounds());

        MemoryProperties.Llm llm = memoryProperties.getLlm();
        if (llm == null) {
            return merged;
        }
        if (!llm.getBaseUrl().isBlank()) {
            merged.setBaseUrl(llm.getBaseUrl());
        }
        if (!llm.getApiKey().isBlank()) {
            merged.setApiKey(llm.getApiKey());
        }
        if (!llm.getModel().isBlank()) {
            merged.setChatModel(llm.getModel());
        }
        if (llm.getTimeoutSeconds() > 0) {
            merged.setTimeoutSeconds(llm.getTimeoutSeconds());
        }
        return merged;
    }




}
