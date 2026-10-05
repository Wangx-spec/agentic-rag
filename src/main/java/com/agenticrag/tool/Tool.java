package com.agenticrag.tool;

import java.util.Map;
import java.util.Set;

/**
 * 工具接口（M3 实现 Agent 工具循环时启用）
 * <p>
 * 每个工具声明名称、描述、参数 JSON Schema 与执行逻辑；
 * Agent 循环把工具描述喂给 LLM，由 LLM 决定是否调用。
 */
public interface Tool {

    /** 工具唯一名称（暴露给 LLM） */
    String name();

    /** 工具功能描述（暴露给 LLM，描述越准确调用越准） */
    String description();

    /** 参数 JSON Schema（用于 LLM function calling 与调用前校验） */
    String parametersSchema();

    /** 工具能力域，供 Agent 按意图过滤可见工具；默认空集合保持兼容。 */
    default Set<ToolDomain> domains() {
        return Set.of();
    }

    /** 执行工具，返回文本结果（作为观察结果拼回上下文） */
    String execute(Map<String, Object> arguments);
}
