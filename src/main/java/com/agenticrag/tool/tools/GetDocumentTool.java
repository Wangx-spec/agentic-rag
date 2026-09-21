package com.agenticrag.tool.tools;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import com.agenticrag.rag.ingest.DocumentRepository;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按文档 ID 读取知识库中某篇文档的正文内容（截断+标注）。
 * 全文来源为 chunks 表拼接（DB 是唯一事实源），fail-open：任何异常转为文本返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetDocumentTool implements Tool {

    private static final String NAME = "get_document";
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "document_id": {
                  "type": "integer",
                  "description": "文档 ID（可从 list_documents 或检索结果中获得）"
                }
              },
              "required": ["document_id"]
            }
            """;

    private final DocumentRepository documentRepository;
    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;

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
        return "按文档 ID 读取知识库中某篇文档的正文内容。当搜索结果指向某篇文档、需要查看完整原文时使用。超长文档会被截断并标注。";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            Object idObj = arguments.get("document_id");
            if (idObj == null) {
                return "错误：缺少 document_id 参数";
            }
            long documentId;
            try {
                documentId = Long.parseLong(idObj.toString().trim());
            } catch (NumberFormatException e) {
                return "错误：document_id 必须是数字，收到：" + idObj;
            }

            Document doc = documentRepository.findById(documentId);
            if (doc == null) {
                return "未找到文档 ID=" + documentId + "，可调用 list_documents 查看可用文档。";
            }
            if (doc.status() != DocumentStatus.DONE || doc.chunkCount() <= 0) {
                return "文档 ID=" + documentId + "（" + doc.name() + "）尚未完成入库，暂不可读。";
            }

            List<Chunk> chunks = documentRepository.findChunksByDocumentId(documentId);
            StringBuilder body = new StringBuilder();
            for (Chunk chunk : chunks) {
                if (chunk.content() != null) {
                    body.append(chunk.content()).append("\n");
                }
            }
            String fullText = body.toString().trim();
            int totalChars = fullText.length();

            int maxChars = ragProperties.getAgent() != null
                    ? ragProperties.getAgent().getDocumentMaxChars() : 8000;
            boolean truncated = totalChars > maxChars;
            String shown = truncated ? fullText.substring(0, maxChars) : fullText;

            StringBuilder sb = new StringBuilder();
            sb.append("文档 ID：").append(doc.id())
                    .append("｜标题：").append(doc.name())
                    .append("｜总字符数：").append(totalChars)
                    .append("｜片段数：").append(doc.chunkCount())
                    .append(truncated ? "｜已截断" : "｜完整")
                    .append("\n\n");
            sb.append(shown);
            if (truncated) {
                sb.append("\n\n（已截断，完整内容共 ").append(totalChars).append(" 字符）");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("get_document 执行失败", e);
            return "读取文档失败：" + e.getMessage();
        }
    }
}
