package com.agenticrag;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.config.McpProperties;
import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.IntentProperties;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.ingest.DocumentRepository;
import com.agenticrag.rag.ingest.IngestTaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, RagProperties.class, McpProperties.class, IntentProperties.class})
public class AgenticRagApplication {

    private static final Logger log = LoggerFactory.getLogger(AgenticRagApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AgenticRagApplication.class, args);
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, IngestTaskQueue.class})
    public ApplicationRunner recoveryRunner(DocumentRepository documentRepository,
                                            IngestTaskQueue ingestTaskQueue) {
        return (ApplicationArguments args) -> {
            int resetCount = documentRepository.resetProcessingToPending();
            if (resetCount > 0) {
                log.warn("启动恢复: 已将 {} 个 PROCESSING 任务回滚为 PENDING", resetCount);
            }

            List<Document> pendingDocs = documentRepository.findPendingOrProcessing();
            if (pendingDocs.isEmpty()) {
                log.info("启动恢复: 无残留任务");
                return;
            }
            log.info("启动恢复: 发现 {} 个残留任务，重新入队", pendingDocs.size());
            for (Document doc : pendingDocs) {
                log.info("  恢复任务: id={}, name={}, status={}", doc.id(), doc.name(), doc.status());
            }
            List<Long> ids = pendingDocs.stream().map(Document::id).toList();
            ingestTaskQueue.submitAll(ids);
        };
    }
}
