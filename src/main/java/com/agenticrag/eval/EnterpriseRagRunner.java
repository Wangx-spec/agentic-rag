package com.agenticrag.eval;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.ingest.IngestService;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.service.ChatEventSink;
import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * EnterpriseRAG-Bench 评测入口（与内置 {@link EvalRunner} 并列，按 eval.bench 开关切换）。
 *
 * <p>两种运行形态：</p>
 * <ul>
 *   <li>零依赖模式：<code>--spring.profiles.active=eval</code>（H2 + 伪向量 + 内存向量，无需基础设施）；</li>
 *   <li>真实栈模式：正常 profile（PG + 真实 embedding + Qdrant），需先起 docker compose 基础设施。</li>
 * </ul>
 *
 * <p>三段流程：</p>
 * <ol>
 *   <li>幂等引导 Confluence 语料（corpus-dir 下 *.md，文件名=dsid，按文档名逐篇幂等）；</li>
 *   <li>逐题走 {@link ChatService}（RAG 模式）作答，提取引用 docName → strip .md → 去重为 document_ids；</li>
 *   <li>逐题追加写 answers.jsonl（官方判分格式），并基于 answers.jsonl 完整重算内部报告。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eval.bench", havingValue = "enterpriserag")
@RequiredArgsConstructor
public class EnterpriseRagRunner implements CommandLineRunner {

    private static final String QUESTIONS_RESOURCE = "eval/datasets/enterprise-rag-64.jsonl";
    private static final int PROGRESS_INTERVAL = 500;

    private final ChatService chatService;
    private final IngestService ingestService;
    private final LlmProperties llmProperties;
    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        if (!llmProperties.isConfigured()) {
            log.error("LLM 未配置，请设置 LLM_API_KEY 环境变量后再运行 EnterpriseRAG 评测");
            return;
        }

        log.info("EnterpriseRAG-Bench 评测启动：bench=enterpriserag");
        bootstrapCorpus();
        int corpusCount = ingestService.listDocuments().size();

        List<EnterpriseRagCase> cases = readQuestions();
        if (cases.isEmpty()) {
            log.warn("未读到任何题目，评测终止");
            return;
        }

        Path answersPath = Path.of(evalProperties.getEnterpriseRag().getAnswersPath());
        Map<String, List<String>> existingAnswers = readAnswerDocIds(answersPath);

        int total = cases.size();
        int remaining = (int) cases.stream().filter(c -> !existingAnswers.containsKey(c.questionId())).count();
        if (remaining == 0) {
            log.info("所有 {} 题均已完成，跳过作答", total);
        }

        int done = 0;
        for (EnterpriseRagCase q : cases) {
            if (existingAnswers.containsKey(q.questionId())) {
                continue;
            }
            try {
                answerOne(answersPath, q);
            } catch (Exception e) {
                log.error("题目 {} 评测失败，跳过: {}", q.questionId(), e.getMessage(), e);
                continue;
            }
            done++;
            log.info("评测进度 {}/{}: {} ({})", done, remaining, q.questionId(), q.questionType());
        }

        writeReport(cases, answersPath, corpusCount);
        log.info("EnterpriseRAG 评测完成，answers={}，report={}",
                answersPath.toAbsolutePath(),
                Path.of(evalProperties.getEnterpriseRag().getReportPath()).toAbsolutePath());
    }

    /** ① 幂等引导语料：按文档名逐篇判断是否已入库，只导入缺失的（真实栈 PG 持久化下断点续跑也正确）。 */
    private void bootstrapCorpus() throws Exception {
        Path corpusDir = Path.of(evalProperties.getEnterpriseRag().getCorpusDir());
        if (!Files.isDirectory(corpusDir)) {
            throw new IllegalStateException("语料目录不存在: " + corpusDir.toAbsolutePath());
        }

        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(corpusDir)) {
            mdFiles = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .toList();
        }
        if (mdFiles.isEmpty()) {
            throw new IllegalStateException("语料目录下没有 .md 文件: " + corpusDir.toAbsolutePath());
        }

        Set<String> loaded = new HashSet<>();
        for (Document doc : ingestService.listDocuments()) {
            loaded.add(doc.name());
        }
        List<Path> toImport = mdFiles.stream()
                .filter(p -> !loaded.contains(p.getFileName().toString()))
                .toList();
        if (toImport.isEmpty()) {
            log.info("EnterpriseRAG 语料已全部入库，跳过引导: {} 篇", mdFiles.size());
            return;
        }

        log.info("开始引导 EnterpriseRAG 语料，待导入 {} 篇（目录共 {} 篇）", toImport.size(), mdFiles.size());
        int done = 0;
        for (Path file : toImport) {
            String fileName = file.getFileName().toString();
            try (InputStream in = Files.newInputStream(file)) {
                Document doc = ingestService.submitTask(fileName, in);
                ingestService.processDocument(doc.id());
            }
            done++;
            if (done % PROGRESS_INTERVAL == 0 || done == toImport.size()) {
                log.info("语料引导进度: {}/{}", done, toImport.size());
            }
        }
        log.info("EnterpriseRAG 语料引导完成，本次导入 {} 篇", done);
    }

    /** ② 单题作答并立即追加写 answers.jsonl（官方判分格式：question_id / answer / document_ids）。 */
    private void answerOne(Path answersPath, EnterpriseRagCase q) throws Exception {
        chatService.clearMemory(q.questionId());
        ChatResult result = chatService.chat(q.questionId(), q.question(), ChatMode.RAG, new ChatEventSink() {});
        List<String> documentIds = extractDocumentIds(result);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question_id", q.questionId());
        row.put("answer", result.answer() == null ? "" : result.answer());
        row.put("document_ids", documentIds);

        String line = objectMapper.writeValueAsString(row);
        if (answersPath.getParent() != null) {
            Files.createDirectories(answersPath.getParent());
        }
        Files.writeString(answersPath, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** 从引用 chunk 提取 dsid：docName（即 dsid.md）→ strip .md → 去重保序。 */
    private List<String> extractDocumentIds(ChatResult result) {
        if (result.sources() == null || result.sources().isEmpty()) {
            return List.of();
        }
        return result.sources().stream()
                .map(RetrievedChunk::docName)
                .filter(Objects::nonNull)
                .map(this::stripMdSuffix)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private String stripMdSuffix(String docName) {
        return docName.toLowerCase(Locale.ROOT).endsWith(".md")
                ? docName.substring(0, docName.length() - 3)
                : docName;
    }

    /** ③ 报告：基于 answers.jsonl 完整重算（幂等，续跑后报告仍完整）。 */
    private void writeReport(List<EnterpriseRagCase> cases, Path answersPath, int corpusCount) throws Exception {
        Map<String, List<String>> answerDocs = readAnswerDocIds(answersPath);

        Map<String, TypeStat> byType = new LinkedHashMap<>();
        List<CaseRow> caseRows = new ArrayList<>();
        double recallSum = 0.0;
        int answered = 0;

        for (EnterpriseRagCase q : cases) {
            if (!answerDocs.containsKey(q.questionId())) {
                continue;
            }
            List<String> retrieved = answerDocs.get(q.questionId());
            double recall = documentRecall(retrieved, q.expectedDocIds());
            byType.computeIfAbsent(q.questionType(), k -> new TypeStat()).add(recall);
            caseRows.add(new CaseRow(q.questionId(), q.questionType(), recall, retrieved.size(), q.expectedDocIds().size()));
            recallSum += recall;
            answered++;
        }

        double macroRecall = answered == 0 ? 0.0 : recallSum / answered;

        StringBuilder sb = new StringBuilder();
        sb.append("# EnterpriseRAG-Bench 报告\n\n");
        sb.append("## 概览\n");
        sb.append("- 语料文档数: ").append(corpusCount).append('\n');
        sb.append("- 题目总数: ").append(cases.size()).append('\n');
        sb.append("- 已作答: ").append(answered).append('\n');
        sb.append("- 文档级 Recall（宏平均）: ").append(percent(macroRecall)).append("\n\n");

        sb.append("## 按题型\n");
        sb.append("| 题型 | 题数 | 文档召回 |\n");
        sb.append("|---|---|---:|\n");
        for (Map.Entry<String, TypeStat> entry : byType.entrySet()) {
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(entry.getValue().count)
                    .append(" | ").append(percent(entry.getValue().avg()))
                    .append(" |\n");
        }

        sb.append("\n## 逐题\n");
        sb.append("| id | 题型 | 命中/期望 | 文档召回 |\n");
        sb.append("|---|---|---:|---:|\n");
        for (CaseRow row : caseRows) {
            sb.append("| ").append(row.id())
                    .append(" | ").append(row.type())
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f/%d", row.recall() * row.expected(), row.expected()))
                    .append(" | ").append(percent(row.recall()))
                    .append(" |\n");
        }

        Path reportPath = Path.of(evalProperties.getEnterpriseRag().getReportPath());
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        Files.writeString(reportPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private double documentRecall(List<String> retrieved, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0.0;
        }
        Set<String> hit = new HashSet<>(retrieved);
        hit.retainAll(expected);
        return (double) hit.size() / expected.size();
    }

    private List<EnterpriseRagCase> readQuestions() throws Exception {
        ClassPathResource resource = new ClassPathResource(QUESTIONS_RESOURCE);
        List<EnterpriseRagCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                cases.add(objectMapper.readValue(line, EnterpriseRagCase.class));
            }
        }
        return cases;
    }

    /** 读 answers.jsonl → {question_id: document_ids}，不存在则返回空 map（同时充当已作答集合）。 */
    private Map<String, List<String>> readAnswerDocIds(Path answersPath) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (!Files.exists(answersPath)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(answersPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String qid = node.path("question_id").asText();
                List<String> docIds = new ArrayList<>();
                JsonNode docs = node.path("document_ids");
                if (docs.isArray()) {
                    for (JsonNode d : docs) {
                        docIds.add(d.asText());
                    }
                }
                map.put(qid, docIds);
            }
        }
        return map;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static final class TypeStat {
        int count = 0;
        double recallSum = 0.0;

        void add(double recall) {
            count++;
            recallSum += recall;
        }

        double avg() {
            return count == 0 ? 0.0 : recallSum / count;
        }
    }

    private record CaseRow(String id, String type, double recall, int retrieved, int expected) {
    }
}
