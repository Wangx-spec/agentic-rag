package com.agenticrag.rag.index;

import com.agenticrag.rag.ingest.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期 BM25 通道健康断言：
 * 跨库比对 SQLite chunks_fts 行数 vs documents(chunk_count>0) 元数据数，
 * 索引为空但元数据存在时 WARN 报警（只告警不阻断启动，由运维决定是否重导）。
 */
@Slf4j
@Component
public class Bm25HealthChecker implements ApplicationRunner {

    private final Bm25Store bm25Store;
    private final DocumentRepository documentRepository;

    public Bm25HealthChecker(Bm25Store bm25Store, DocumentRepository documentRepository) {
        this.bm25Store = bm25Store;
        this.documentRepository = documentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        long bm25Rows = bm25Store.countChunks();
        long ingestedDocs = documentRepository.countIngested();
        if (bm25Rows == 0 && ingestedDocs > 0) {
            log.warn("BM25 索引为空但元数据存在（已完成入库文档 {} 篇），索引可能未正确初始化", ingestedDocs);
        } else if (bm25Rows == 0) {
            log.info("BM25 索引当前为空（尚无已入库文档），属初始化状态");
        } else {
            log.info("BM25 通道健康检查通过：索引行数={}，已入库文档={} 篇", bm25Rows, ingestedDocs);
        }
    }
    
}
