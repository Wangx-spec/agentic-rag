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
 * 限量列出知识库中已入库完成的文档（ID/标题/摘要/片段数）。
 * 只列 DONE 且 chunkCount>0 的文档；fail-open：任何异常转为文本返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListDocumentsTool implements Tool {

    private static final String NAME = "list_documents";
    private static final int SUMMARY_CHARS = 80;
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "limit": {
                  "type": "integer",
                  "description": "返回篇数上限（可选，默认取系统配置）"
                }
              }
            }
            """;

    private final DocumentRepository documentRepository;
    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;

    @PostConstruct
    public void register() {
        if (!documentToolsEnabled()) {
            log.info("document-tools-enabled=false，跳过注册工具: {}", NAME);
            return;
        }
        toolRegistry.register(this);
    }

    private boolean documentToolsEnabled() {
        return ragProperties.getAgent() == null || ragProperties.getAgent().isDocumentToolsEnabled();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出知识库中已入库的文档清单（ID、标题、摘要、片段数）。想了解知识库里有什么、或需要找到某篇文档的 ID 时使用；拿到 ID 后用 get_document 读全文。";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            int configuredLimit = ragProperties.getAgent() != null
                    ? ragProperties.getAgent().getListDocumentsLimit() : 20;
            int limit = configuredLimit;
            Object limitObj = arguments.get("limit");
            if (limitObj != null) {
                try {
                    int requested = Integer.parseInt(limitObj.toString().trim());
                    if (requested > 0) {
                        limit = Math.min(requested, configuredLimit);
                    }
                } catch (NumberFormatException e) {
                    // 非法 limit 忽略，用默认值
                }
            }

            List<Document> done = documentRepository.findAll().stream()
                    .filter(d -> d.status() == DocumentStatus.DONE && d.chunkCount() > 0)
                    .toList();
            if (done.isEmpty()) {
                return "知识库暂无已入库文档。";
            }

            List<Document> shown = done.subList(0, Math.min(limit, done.size()));
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(done.size()).append(" 篇文档（已入库完成），显示前 ")
                    .append(shown.size()).append(" 篇：\n");
            for (Document doc : shown) {
                sb.append("[").append(doc.id()).append("] ")
                        .append(doc.name())
                        .append("（").append(doc.chunkCount()).append(" 个片段）");
                String summary = firstChunkSummary(doc.id());
                if (!summary.isEmpty()) {
                    sb.append(" ").append(summary);
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("list_documents 执行失败", e);
            return "列出文档失败：" + e.getMessage();
        }
    }

    /** 首 chunk 截 80 字符作摘要；取不到时返回空串（单篇失败不影响整体） */
    private String firstChunkSummary(Long documentId) {
        try {
            List<Chunk> chunks = documentRepository.findChunksByDocumentId(documentId);
            if (chunks.isEmpty() || chunks.get(0).content() == null) {
                return "";
            }
            String content = chunks.get(0).content().trim();
            return content.length() > SUMMARY_CHARS
                    ? content.substring(0, SUMMARY_CHARS) + "…" : content;
        } catch (Exception e) {
            log.warn("取文档 {} 摘要失败", documentId, e);
            return "";
        }
    }
}
