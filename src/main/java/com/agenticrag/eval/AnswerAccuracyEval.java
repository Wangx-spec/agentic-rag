package com.agenticrag.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AnswerAccuracyEval {

    public Result evaluate(String answer, String referenceAnswer) {
        List<String> answerTokens = tokenize(answer);
        List<String> referenceTokens = tokenize(referenceAnswer);
        if (answerTokens.isEmpty() || referenceTokens.isEmpty()) {
            return new Result(0.0, List.of(), referenceTokens, answerTokens);
        }

        Map<String, Integer> answerFreq = toFreq(answerTokens);
        Map<String, Integer> referenceFreq = toFreq(referenceTokens);
        List<String> matched = new ArrayList<>();
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : referenceFreq.entrySet()) {
            int hits = Math.min(entry.getValue(), answerFreq.getOrDefault(entry.getKey(), 0));
            overlap += hits;
            for (int i = 0; i < hits; i++) {
                matched.add(entry.getKey());
            }
        }
        double precision = overlap == 0 ? 0.0 : (double) overlap / answerTokens.size();
        double recall = overlap == 0 ? 0.0 : (double) overlap / referenceTokens.size();
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new Result(f1, matched, referenceTokens, answerTokens);
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\[[0-9]+\\]", " ")
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isAsciiTokenChar(ch)) {
                latin.append(ch);
                continue;
            }
            if (latin.length() > 0) {
                tokens.add(latin.toString());
                latin.setLength(0);
            }
            if (Character.isWhitespace(ch)) {
                continue;
            }
            tokens.add(String.valueOf(ch));
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString());
        }
        return tokens;
    }

    private static boolean isAsciiTokenChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }

    private static Map<String, Integer> toFreq(List<String> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokens) {
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }

    public record Result(double f1, List<String> matchedTokens, List<String> referenceTokens, List<String> answerTokens) {
        public boolean passed(double threshold) {
            return f1 >= threshold;
        }
    }
}
