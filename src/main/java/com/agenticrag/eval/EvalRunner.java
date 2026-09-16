package com.agenticrag.eval;

import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.ingest.IngestService;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.service.ChatEventSink;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ChatService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@Profile("eval")
@ConditionalOnProperty(name = "eval.bench", havingValue = "internal", matchIfMissing = true)
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    private static final double ANSWER_PASS_THRESHOLD = 0.50;
    private static final List<String> EVAL_CORPUS = List.of(
            "01-rag-overview.md",
            "02-citations.md",
            "03-agent-loop.md",
            "04-memory.md",
            "05-multi-agent.md",
            "06-intent-routing.md",
            "07-qdrant.md",
            "08-docker-compose.md",
            "09-security.md",
            "10-evaluation.md",
            "11-frontend-modes.md",
            "12-calculator.md"
    );

    private final ChatService chatService;
    private final IngestService ingestService;
    private final AnswerAccuracyEval answerAccuracyEval;
    private final RecallEval recallEval;
    private final CitationEval citationEval;
    private final ToolSuccessEval toolSuccessEval;
    private final IntentRoutingEval intentRoutingEval;
    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        log.info("Eval profile 启动：准备引导内置语料并执行离线评测");
        bootstrapCorpus();

        List<EvalCase> evalCases = readJsonList("eval/datasets/eval-set.json", new TypeReference<>() {});
        List<IntentEvalCase> intentCases = readJsonList("eval/datasets/intent-set.json", new TypeReference<>() {});
        List<CaseReport> caseReports = new ArrayList<>();

        long answerPassed = 0;
        long refusalPassed = 0;
        long toolPassed = 0;
        double recallSum = 0.0;
        double citationSum = 0.0;

        for (EvalCase evalCase : evalCases) {
            chatService.clearMemory(evalCase.id());
            Instant start = Instant.now();
            ChatResult result = chatService.chat(evalCase.id(), evalCase.question(), evalCase.mode(), new SilentSink());
            Duration duration = Duration.between(start, Instant.now());

            var accuracy = answerAccuracyEval.evaluate(result.answer(), evalCase.referenceAnswer());
            var recall = recallEval.evaluate(result.sources(), evalCase.sourceChunkIds());
            var citation = citationEval.evaluate(result.answer(), result.sources());
            var tool = toolSuccessEval.evaluate(result, evalCase.expectTool());
            boolean refusalOk = !evalCase.expectRefusal() || looksLikeRefusal(result.answer());

            if (accuracy.passed(ANSWER_PASS_THRESHOLD)) {
                answerPassed++;
            }
            if (refusalOk) {
                refusalPassed++;
            }
            if (tool.passed()) {
                toolPassed++;
            }
            recallSum += recall.recallAt10();
            citationSum += citation.validRate();

            caseReports.add(new CaseReport(
                    evalCase.id(),
                    evalCase.mode().name(),
                    evalCase.question(),
                    result.answer(),
                    accuracy.f1(),
                    recall.recallAt10(),
                    citation.validRate(),
                    tool.passed(),
                    refusalOk,
                    duration.toMillis()
            ));
        }

        var intentReport = intentRoutingEval.evaluate(intentCases);
        int total = evalCases.size();
        String markdown = buildReport(
                total == 0 ? 0.0 : (double) answerPassed / total,
                total == 0 ? 0.0 : recallSum / total,
                total == 0 ? 0.0 : citationSum / total,
                total == 0 ? 0.0 : (double) toolPassed / total,
                intentReport.accuracy(),
                total == 0 ? 0.0 : (double) refusalPassed / total,
                caseReports,
                intentReport
        );

        Path reportPath = Path.of(evalProperties.getReportPath());
        Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
        log.info("Eval 完成，报告已写入 {}", reportPath.toAbsolutePath());
    }

    private void bootstrapCorpus() throws Exception {
        List<Document> existing = ingestService.listDocuments();
        if (!existing.isEmpty()) {
            log.info("Eval 语料已存在，跳过引导: {} 篇", existing.size());
            return;
        }
        for (String fileName : EVAL_CORPUS) {
            ClassPathResource resource = new ClassPathResource("eval/corpus/" + fileName);
            try (InputStream inputStream = resource.getInputStream()) {
                Document doc = ingestService.submitTask(fileName, inputStream);
                ingestService.processDocument(doc.id());
            }
        }
        List<Document> loaded = new ArrayList<>(ingestService.listDocuments());
        loaded.sort(Comparator.comparing(Document::id));
        log.info("Eval 语料引导完成，共 {} 篇", loaded.size());
    }

    private <T> List<T> readJsonList(String resourcePath, TypeReference<List<T>> typeReference) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, typeReference);
        }
    }

    private boolean looksLikeRefusal(String answer) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return normalized.contains("未提及")
                || normalized.contains("不足")
                || normalized.contains("抱歉")
                || normalized.contains("无法")
                || normalized.contains("没法")
                || normalized.contains("没有找到");
    }

    private String buildReport(double answerAccuracy,
                               double recallAt10,
                               double citationValidity,
                               double toolSuccess,
                               double intentAccuracy,
                               double refusalAccuracy,
                               List<CaseReport> cases,
                               IntentRoutingEval.Report intentReport) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval Report\n\n");
        sb.append("## Summary\n");
        sb.append("- 回答准确率: ").append(percent(answerAccuracy)).append('\n');
        sb.append("- Recall@10: ").append(percent(recallAt10)).append('\n');
        sb.append("- 引用有效率: ").append(percent(citationValidity)).append('\n');
        sb.append("- 工具调用成功率: ").append(percent(toolSuccess)).append('\n');
        sb.append("- 意图路由准确率: ").append(percent(intentAccuracy)).append('\n');
        sb.append("- 拒答通过率: ").append(percent(refusalAccuracy)).append("\n\n");

        sb.append("## Cases\n");
        sb.append("| id | mode | F1 | Recall | Citation | Tool | Refusal | Cost(ms) |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (CaseReport report : cases) {
            sb.append("| ").append(report.id())
                    .append(" | ").append(report.mode())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", report.answerF1()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", report.recallAt10()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", report.citationValidRate()))
                    .append(" | ").append(report.toolPassed() ? "Y" : "N")
                    .append(" | ").append(report.refusalPassed() ? "Y" : "N")
                    .append(" | ").append(report.costMs())
                    .append(" |\n");
        }

        sb.append("\n## Intent Routing\n");
        sb.append("| id | expected | actual | confidence | pass |\n");
        sb.append("|---|---|---|---:|---:|\n");
        for (IntentRoutingEval.CaseResult result : intentReport.results()) {
            sb.append("| ").append(result.id())
                    .append(" | ").append(result.expectedIntent())
                    .append(" | ").append(result.actualIntent())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.confidence()))
                    .append(" | ").append(result.passed() ? "Y" : "N")
                    .append(" |\n");
        }
        return sb.toString();
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private record CaseReport(
            String id,
            String mode,
            String question,
            String answer,
            double answerF1,
            double recallAt10,
            double citationValidRate,
            boolean toolPassed,
            boolean refusalPassed,
            long costMs
    ) {
    }

    private static final class SilentSink implements ChatEventSink {
        @Override
        public void onDone(List<RetrievedChunk> sources) {
        }
    }
}
