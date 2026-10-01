package com.agenticrag.tool.tools;

import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.memory.MemoryScope;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆检索工具（M9/T9）：供 Agent 主动回忆当前用户的跨会话记忆。
 * <p>
 * 用户身份取自 {@link MemoryScope}（N3 隔离，不越权检索他人在库内容）；
 * 非请求线程（多 Agent 子任务等）无上下文时返回友好降级提示（N1），不阻断主链路。
 * <p>
 * 仅在 rag.memory.type=jdbc 时装配（长期记忆依赖 PG + Qdrant 存储）。
 * 与 search_knowledge_base 的关键差异：记忆内容不作为知识库引用来源，无需标注 [n] 引用编号。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class SearchMemoryTool implements Tool {

    private static final String NAME = "search_memory";
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "用于语义检索长期记忆的查询文本，例如：用户此前的城市偏好"
                }
              },
              "required": ["query"]
            }
            """;

    private final LongTermMemoryService longTermMemoryService;
    private final MemoryProperties memoryProperties;
    private final ToolRegistry toolRegistry;

    @PostConstruct
    public void register() {
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索当前用户的长期记忆（跨会话保留的结论性事实与偏好）。"
                + "当问题涉及用户自身情况或此前对话确认过的内容时使用；记忆内容无需标注引用编号。";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    /**
     * 执行检索：query 必填；用户身份依赖 ThreadLocal 上下文，缺失时降级为提示文本。
     * @param arguments 工具参数（包含 query 字段）
     * @return 渲染后的观察结果文本
     */
    @Override
    public String execute(Map<String, Object> arguments) {
        Object queryObj = arguments.get("query");
        if (queryObj == null || queryObj.toString().isBlank()) {
            return "错误：缺少 query 参数";
        }
        MemoryScope.Context ctx = MemoryScope.current();
        if (ctx == null) {
            return "当前无会话上下文，无法检索个人长期记忆（多 Agent 子任务场景暂不支持），请基于当前对话与知识库作答。";
        }
        List<String> hits = longTermMemoryService.search(ctx.userId(), queryObj.toString(),
                memoryProperties.getLtmSearchTopk());
        return render(hits);
    }

    /**
     * 渲染检索到的长期记忆条目（编号便于主模型观察）。
     * @param hits 记忆内容列表
     * @return 渲染后的字符串
     */
    public String render(List<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关长期记忆。";
        }
        StringBuilder sb = new StringBuilder("检索到 ").append(hits.size()).append(" 条长期记忆：\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(hits.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
}
