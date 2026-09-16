package com.agenticrag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * EnterpriseRAG-Bench 单条评测题（仅保留评测所需字段，其余由 Jackson 忽略）
 *
 * @param questionId       题目 ID（对应官方 questions.jsonl 的 question_id）
 * @param questionType     题型：basic / semantic / constrained / completeness / misc / intra / project / conflicting
 * @param question         英文题干
 * @param expectedDocIds   期望命中的 gold 文档 dsid 列表（判分 document recall 的命门）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnterpriseRagCase(
        @JsonProperty("question_id") String questionId,
        @JsonProperty("question_type") String questionType,
        @JsonProperty("question") String question,
        @JsonProperty("expected_doc_ids") List<String> expectedDocIds
) {
}
