package com.agenticrag.rag.ingest;

import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 文档摄取任务队列
 * 用于存储待处理的文档任务
 */
@Component
public class IngestTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskQueue.class);
    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ingest-worker");
        t.setDaemon(true);
        return t;
    });
    
    private final IngestService ingestService;

    public IngestTaskQueue(IngestService ingestService) {
        this.ingestService = ingestService;
        executor.submit(this::workerLoop);
    }

    public void submitAll(java.util.List<Long> documentIds) {
        queue.addAll(documentIds);
    }

    public void submit(Long documentId) {
        queue.add(documentId);
    }

    public int pendingCount() {
        return queue.size();
    }

    public void workerLoop(){
        while (!Thread.currentThread().isInterrupted()){
            try {
                Long documentId = queue.poll(30, TimeUnit.SECONDS);
                if (documentId == null){
                    continue;
                }
                ingestService.processDocument(documentId);
                log.info("Document {} processed", documentId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("后台处理文档失败", e);
            }
        }
    }
}
