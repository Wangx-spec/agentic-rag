package com.agenticrag.agent;

import java.util.Map;

public interface StepReporter {

    default void onThinking(String toolName) {}
    default void onActing(String toolName, String arguments) {}
    default void onObserving(String summary) {}
    default void onFinal(String message) {}

    /** S3.1：工具表格结果（columns/rows/sql/truncated），经 AgentLoop drain 后由 sink 发 SSE table 事件。 */
    default void onTable(Map<String, Object> payload) {}

    /** S3.2：工具证据编号，供前端展示证据清单。 */
    default void onEvidence(EvidenceRegistry.Evidence evidence) {}

}
