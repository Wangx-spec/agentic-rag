package com.agenticrag.tool.tools;

import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SearchKnowledgeBaseTool implements Tool {

    private static final String NAME = "search_knowledge_base";
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "用于搜索知识库的自然语言查询"
                }
              },
              "required": ["query"]
            }
            """;
    private final HybridRetriever hybridRetriever;
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
        return "搜索本地知识库，返回最相关的文本片段（带编号和来源文档名）";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    /**
     * 执行工具，返回文本结果（作为观察结果拼回上下文）。
     * @param arguments 工具参数（包含 query 字段）
     * @return 渲染后的字符串
     * */
    @Override
    public String execute(Map<String, Object> arguments) {
        Object queryObj = arguments.get("query");
        if (queryObj == null) {
            return "错误：缺少 query 参数";
        }
        String query = queryObj.toString();
        String result = render(search(query));
        return result;
    }

    /**
     * 搜索本地知识库，返回最相关的文本片段。
     * @param query 查询字符串
     * @return 检索到的文本片段列表
     */
    public List<RetrievedChunk> search(String query) {
        return hybridRetriever.retrieve(query);
    }

    /**
     * 渲染检索到的文本片段，带编号和来源文档名。
     * @param chunks 检索到的文本片段列表
     * @return 渲染后的字符串
     * */
    public String render(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "未检索到相关片段。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("检索到 ").append(chunks.size()).append(" 条相关片段：\n");
        for (RetrievedChunk chunk : chunks) {
            sb.append("[").append(chunk.rank()).append("] ")
                    .append(chunk.docName()).append(":")
                    .append(chunk.content()).append("\n");
        }
        return sb.toString().trim();
    }

}
