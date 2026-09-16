package com.agenticrag.eval;

import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationEval {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    public Result evaluate(String answer, List<RetrievedChunk> sources) {
        Set<Integer> cited = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            cited.add(Integer.parseInt(matcher.group(1)));
        }

        Set<Integer> validRanks = new LinkedHashSet<>();
        for (RetrievedChunk source : sources == null ? List.<RetrievedChunk>of() : sources) {
            validRanks.add(source.rank());
        }

        Set<Integer> invalid = new LinkedHashSet<>(cited);
        invalid.removeAll(validRanks);

        double validity = cited.isEmpty() ? (validRanks.isEmpty() ? 1.0 : 0.0) : (double) (cited.size() - invalid.size()) / cited.size();
        return new Result(validity, cited, invalid);
    }

    public record Result(double validRate, Set<Integer> citedReferences, Set<Integer> invalidReferences) {
    }
}
