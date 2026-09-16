package com.agenticrag.eval;

import com.agenticrag.intent.IntentClassifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IntentRoutingEval {

    private final IntentClassifier intentClassifier;

    public IntentRoutingEval(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    public Report evaluate(List<IntentEvalCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        for (IntentEvalCase evalCase : cases) {
            var classified = intentClassifier.classify(evalCase.question());
            boolean passed = classified.intent() == evalCase.expectedIntent();
            results.add(new CaseResult(evalCase.id(), evalCase.expectedIntent().name(), classified.intent().name(), classified.confidence(), passed));
        }
        long passedCount = results.stream().filter(CaseResult::passed).count();
        double accuracy = results.isEmpty() ? 0.0 : (double) passedCount / results.size();
        return new Report(accuracy, results);
    }

    public record Report(double accuracy, List<CaseResult> results) {
    }

    public record CaseResult(String id, String expectedIntent, String actualIntent, double confidence, boolean passed) {
    }
}
