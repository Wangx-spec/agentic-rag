package com.agenticrag.eval.da;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DaEvalQuestion(
        @JsonProperty("question_id") String questionId,
        String question,
        String type,
        @JsonProperty("expected_sql_pattern") String expectedSqlPattern,
        @JsonProperty("expected_points") List<String> expectedPoints,
        @JsonProperty("expected_evidence_type") List<String> expectedEvidenceType,
        @JsonProperty("expected_behavior") String expectedBehavior
) {
    public DaEvalQuestion {
        expectedPoints = expectedPoints == null ? List.of() : List.copyOf(expectedPoints);
        expectedEvidenceType = expectedEvidenceType == null ? List.of() : List.copyOf(expectedEvidenceType);
        expectedBehavior = expectedBehavior == null || expectedBehavior.isBlank() ? "answer" : expectedBehavior;
    }
}
