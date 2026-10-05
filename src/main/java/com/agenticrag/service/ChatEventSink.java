package com.agenticrag.service;

import com.agenticrag.agent.EvidenceRegistry;
import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.List;
import java.util.Map;

public interface ChatEventSink {

    default void onThinking(String text) {
    }

    default void onDelta(String text) {
    }

    default void onDone(List<RetrievedChunk> sources) {
    }

    default void onError(String message) {
    }

    /** S3.1：结构化表格结果（columns/rows/sql/truncated），前端渲染表格与图表。 */
    default void onTable(Map<String, Object> payload) {
    }

    /** S3.2：证据编号事件，前端展示可回溯证据列表。 */
    default void onEvidence(EvidenceRegistry.Evidence evidence) {
    }
}
