package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SubAgent：单个子问题的轻量执行器 —— 检索 + 子结论生成，固定两步管线。
 * <p>
 * <b>worker 语义</b>：这不是自主 agent，不递归 AgentLoop、不做工具循环。
 * Leader 已把问题拆到原子粒度，再让 LLM 决定「要不要调工具」只是白付一轮 token 与延迟。
 * 若将来子任务需要工具迭代，内部实现可切换为「构造 scoped AgentContext 委派 AgentLoop.run()」，
 * {@code execute} 接口不变，编排层零改动。
 * <p>
 * <b>失败契约</b>：本方法不抛异常。检索为空、LLM 失败一律返回 {@code success=false}，
 * 由编排器决定是否降级（全失败回退普通 RAG）。线程池并行下，抛异常会被
 * CompletableFuture 包成 CompletionException，反而丢掉「哪个子任务、为什么失败」的上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentExecutor {

    private static final String SUB_CONCLUSION_PROMPT_TEMPLATE = """
            你是一个基于给定上下文回答问题的中文助手。请只围绕「子问题」作答，优先利用给定片段，并在句末用 [n] 标注引用编号。

            要求：
            1. 只回答子问题本身，不要展开无关内容，也不要提及「子问题」这一说法
            2. 片段中没有的信息不要编造；上下文不足时明确说明「资料中未提及」
            3. 结论控制在 200 字以内，供后续汇总使用

            子问题：%s

            给定片段：
            %s
            """;

    private final HybridRetriever hybridRetriever;
    private final LlmClient llmClient;

    /**
     * 执行单个子任务：检索 → 生成子结论
     *
     * @param subQuestion 子问题（Leader 已保证自包含，不含指代）
     * @param index       子任务序号，从 1 开始，仅用于事件/日志标识
     * @return 执行结果
     */
    public SubTaskResult execute(String subQuestion, int index) {
        if (subQuestion == null || subQuestion.isBlank()) {
            return SubTaskResult.failure(index, subQuestion, "子问题为空");
        }
        try {
            List<RetrievedChunk> chunks = hybridRetriever.retrieve(subQuestion);
            if (chunks == null || chunks.isEmpty()) {
                log.info("子任务 {} 检索无结果: {}", index, subQuestion);
                return SubTaskResult.failure(index, subQuestion, "检索无结果");
            }

            String prompt = buildSubConclusionPrompt(subQuestion, chunks);
            String conclusion = generateSubConclusion(List.of(ChatMessage.system(prompt)));
            if (conclusion == null || conclusion.isBlank()) {
                return SubTaskResult.failure(index, subQuestion, "子结论为空");
            }

            log.info("子任务 {} 完成: question={}, sources={}", index, subQuestion, chunks.size());
            return SubTaskResult.ok(index, subQuestion, conclusion.trim(), chunks);
        } catch (Exception e) {
            log.warn("子任务 {} 执行失败: question={}", index, subQuestion, e);
            return SubTaskResult.failure(index, subQuestion, e.getMessage());
        }
    }

    /**
     * 构造「子问题 + 检索片段 → 带 [n] 引用的结论」prompt
     * <p>
     * 片段编号沿用 {@link RetrievedChunk#rank()}，与 ChatController / SearchKnowledgeBaseTool
     * 的渲染格式保持一致，前端引用面板无需适配。
     */
    private String buildSubConclusionPrompt(String subQuestion, List<RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            context.append("[").append(chunk.rank()).append("] ")
                    .append(chunk.docName()).append("：")
                    .append(chunk.content()).append("\n\n");
        }
        return SUB_CONCLUSION_PROMPT_TEMPLATE.formatted(subQuestion, context.toString().trim());
    }

    /**
     * 同步调用 LLM 生成子结论（非流式）
     * <p>
     * 子结论是中间产物，不直接面向用户，无需流式；最终回答的流式输出由 Aggregator 负责。
     */
    private String generateSubConclusion(List<ChatMessage> messages) {
        return llmClient.chat(messages);
    }
}