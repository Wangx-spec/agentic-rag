package com.agenticrag.rag.ingest;

import java.util.List;
import com.agenticrag.rag.dto.Chunk;

/**
 * 分块器：把长文本切成适合检索的块
 * <p>
 * 计划方法：
 * - List&lt;Chunk&gt; split(String text, long documentId)   分块（含重叠窗口保留上下文）
 */
public interface Chunker {
    List<Chunk> split(String text, String docName);
}
