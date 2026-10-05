package com.agenticrag.eval.da;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DaEvalAnswer(
        @JsonProperty("question_id") String questionId,
        String answer,
        @JsonProperty("evidence_ids") List<String> evidenceIds
) {
    public DaEvalAnswer {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
