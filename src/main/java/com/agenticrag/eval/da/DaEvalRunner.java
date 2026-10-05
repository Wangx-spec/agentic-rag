package com.agenticrag.eval.da;

import com.agenticrag.agent.EvidenceRegistry;
import com.agenticrag.eval.EvalProperties;
import com.agenticrag.eval.trace.EvalTraceContext;
import com.agenticrag.eval.trace.RunTraceRepository;
import com.agenticrag.eval.trace.TraceEvent;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.service.ChatEventSink;
import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Profile("eval")
@ConditionalOnProperty(name = "eval.bench", havingValue = "dataanalysis")
@RequiredArgsConstructor
public class DaEvalRunner implements CommandLineRunner {

    private final ChatService chatService;
    private final EvalProperties evalProperties;
    private final RunTraceRepository runTraceRepository;
    private final RuleChecker ruleChecker;
    private final DaJudge daJudge;
    private final MetricsDeriver metricsDeriver;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        String action = evalProperties.getDa().getAction();
        if ("judge".equalsIgnoreCase(action)) {
            judge();
        } else {
            runQuestions();
        }
    }

    private void runQuestions() throws Exception {
        String runId = evalProperties.getDa().getRunId();
        List<DaEvalQuestion> questions = readQuestions();
        int limit = evalProperties.getDa().getLimit();
        if (limit > 0 && questions.size() > limit) {
            questions = questions.subList(0, limit);
        }
        Path output = roundsDir().resolve(runId + ".jsonl");
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        log.info("S3.3 数据分析评测启动：runId={}, questions={}", runId, questions.size());
        for (DaEvalQuestion question : questions) {
            RecordingSink sink = new RecordingSink();
            try {
                EvalTraceContext.set(runId, question.questionId());
                chatService.clearMemory("da-" + question.questionId());
                ChatResult result = chatService.chat("da-" + question.questionId(), question.question(), ChatMode.AGENT, sink);
                List<String> evidenceIds = new ArrayList<>(sink.evidenceIds());
                for (RetrievedChunk source : result.sources()) {
                    String id = String.valueOf(source.rank());
                    if (!evidenceIds.contains(id)) {
                        evidenceIds.add(id);
                    }
                }
                lines.add(objectMapper.writeValueAsString(new DaEvalAnswer(question.questionId(), result.answer(), evidenceIds)));
            } finally {
                EvalTraceContext.clear();
            }
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
        log.info("S3.3 数据分析评测完成：answers={}", output.toAbsolutePath());
    }

    private void judge() throws Exception {
        String runId = evalProperties.getDa().getRunId();
        List<DaEvalQuestion> questions = readQuestions();
        Map<String, DaEvalAnswer> answers = readAnswers(roundsDir().resolve(runId + ".jsonl"));
        List<TraceEvent> traces = runTraceRepository.findByRunId(runId);
        Map<String, List<TraceEvent>> tracesByQuestion = metricsDeriver.groupByQuestion(traces);

        List<RuleChecker.RuleResult> ruleResults = new ArrayList<>();
        List<DaJudge.JudgeResult> judgeResults = new ArrayList<>();
        for (DaEvalQuestion question : questions) {
            DaEvalAnswer answer = answers.getOrDefault(question.questionId(),
                    new DaEvalAnswer(question.questionId(), "", List.of()));
            ruleResults.add(ruleChecker.check(question, answer,
                    tracesByQuestion.getOrDefault(question.questionId(), List.of())));
            judgeResults.add(daJudge.judge(question, answer));
        }
        MetricsDeriver.Report report = metricsDeriver.derive(questions, traces, ruleResults, judgeResults);
        String markdown = metricsDeriver.renderMarkdown(runId, questions, ruleResults, judgeResults, report);
        Path reportPath = roundsDir().resolve(runId + "-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
        log.info("S3.3 数据分析判分完成：report={}", reportPath.toAbsolutePath());
    }

    private List<DaEvalQuestion> readQuestions() throws Exception {
        Path path = Path.of(evalProperties.getDa().getQuestionsFile());
        List<DaEvalQuestion> result = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                result.add(objectMapper.readValue(line, DaEvalQuestion.class));
            }
        }
        result.sort(Comparator.comparing(DaEvalQuestion::questionId));
        return result;
    }

    private Map<String, DaEvalAnswer> readAnswers(Path path) throws Exception {
        Map<String, DaEvalAnswer> result = new HashMap<>();
        if (!Files.exists(path)) {
            return result;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                DaEvalAnswer answer = objectMapper.readValue(line, DaEvalAnswer.class);
                result.put(answer.questionId(), answer);
            }
        }
        return result;
    }

    private Path roundsDir() {
        return Path.of(evalProperties.getDa().getRoundsDir());
    }

    private static final class RecordingSink implements ChatEventSink {
        private final List<String> evidenceIds = new ArrayList<>();

        @Override
        public void onEvidence(EvidenceRegistry.Evidence evidence) {
            if (evidence != null) {
                evidenceIds.add(evidence.id());
            }
        }

        private List<String> evidenceIds() {
            return List.copyOf(evidenceIds);
        }
    }
}
