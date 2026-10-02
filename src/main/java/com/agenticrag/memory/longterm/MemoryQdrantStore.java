package com.agenticrag.memory.longterm;

import com.agenticrag.config.RagProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.HnswConfigDiff;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchParams;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.WithVectorsSelector;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 长期记忆向量存储（M9 F4，技术决策 7）：独立集合 agentic_rag_memories，
 * 不复用面向 chunk 的 VectorStore 接口——知识库检索与记忆检索的 payload/filter/淘汰策略完全不同。
 * <p>
 * 集合隔离：payload 带 ownerId（= userId，与 M8 检索 filter 约定一致），
 * 检索强制 filter(ownerId=userId)；HNSW 参数与维度复用 rag.vector.qdrant.* 与 rag.embeddingDim。
 * 仅在 jdbc 记忆模式 + qdrant 向量模式同时满足时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class MemoryQdrantStore {

    private static final String COLLECTION = "agentic_rag_memories";

    private final RagProperties ragProperties;
    private volatile QdrantClient client;

    public MemoryQdrantStore(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        try {
            ensureCollection();
        } catch (DimensionMismatchException e) {
            // 维度不一致 = 配置错误（embedding 模型与 rag.embedding-dim 错配），
            // 与知识库侧 QdrantStore 保持一致：fail-fast，启动即失败，不走降级。
            throw e;
        } catch (Exception e) {
            // N1：运行时故障（连接失败等）不阻断启动，检索/写入时按降级处理
            log.warn("记忆向量集合初始化失败，长期记忆功能将降级: {}", e.getMessage());
        }
    }

    public void upsert(long pointId, float[] vector, long ownerId, String content, long createdAtEpochMs) {
        Map<String, Value> payload = new HashMap<>();
        payload.put("ownerId", ValueFactory.value(ownerId));
        payload.put("content", ValueFactory.value(content));
        payload.put("createdAt", ValueFactory.value(createdAtEpochMs));

        PointStruct point = PointStruct.newBuilder()
                .setId(PointId.newBuilder().setNum(pointId).build())
                .setVectors(Vectors.newBuilder()
                        .setVector(Vector.newBuilder().addAllData(toList(vector)).build())
                        .build())
                .putAllPayload(payload)
                .build();
        await(client().upsertAsync(COLLECTION, List.of(point)));
    }

    /** 按 ownerId 过滤的语义检索，返回 (pointId, content, score) */
    public List<MemoryHit> search(float[] queryVector, long ownerId, int topK) {
        Filter filter = Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("ownerId")
                                .setMatch(Match.newBuilder().setInteger(ownerId).build())
                                .build())
                        .build())
                .build();
        SearchPoints request = SearchPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .addAllVector(toList(queryVector))
                .setLimit(topK)
                .setFilter(filter)
                .setParams(SearchParams.newBuilder()
                        .setHnswEf(ragProperties.getVector().getQdrant().getSearchEf())
                        .build())
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build())
                .build();

        List<ScoredPoint> results = await(client().searchAsync(request));
        List<MemoryHit> hits = new ArrayList<>();
        for (ScoredPoint point : results) {
            Value content = point.getPayloadMap().get("content");
            hits.add(new MemoryHit(point.getId().getNum(),
                    content == null ? "" : content.getStringValue(), point.getScore()));
        }
        return hits;
    }

    public void delete(List<Long> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        // 客户端仅提供 (collection, ids) 与 (collection, filter) 两个重载，按 id 列表直删
        List<PointId> ids = new ArrayList<>(pointIds.size());
        for (Long id : pointIds) {
            ids.add(PointId.newBuilder().setNum(id).build());
        }
        await(client().deleteAsync(COLLECTION, ids));
    }

    public record MemoryHit(long pointId, String content, float score) {
    }

    private void ensureCollection() {
        boolean exists = await(client().collectionExistsAsync(COLLECTION));
        if (exists) {
            long actual = currentDim();
            int expected = ragProperties.getEmbeddingDim();
            if (actual != expected) {
                throw new DimensionMismatchException(
                        "Qdrant 记忆集合 " + COLLECTION + " 向量维度为 " + actual
                                + "，但当前配置 rag.embedding-dim=" + expected
                                + "。请确认 embedding 模型与 rag.embedding-dim 配置一致；"
                                + "若确已更换 embedding 模型，需先迁移旧记忆数据再删除旧集合重建。");
            }
            return;
        }
        CreateCollection request = CreateCollection.newBuilder()
                .setCollectionName(COLLECTION)
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParams(VectorParams.newBuilder()
                                .setSize(ragProperties.getEmbeddingDim())
                                .setDistance(Distance.Cosine)
                                .setHnswConfig(HnswConfigDiff.newBuilder()
                                        .setM(ragProperties.getVector().getQdrant().getHnswM())
                                        .setEfConstruct(ragProperties.getVector().getQdrant().getEfConstruct())
                                        .build())
                                .build())
                        .build())
                .build();
        await(client().createCollectionAsync(request));
        log.info("记忆向量集合已创建: {}, dim={}", COLLECTION, ragProperties.getEmbeddingDim());
    }

    /** 读取已存在集合的实际向量维度（复用知识库侧 QdrantStore 同款校验逻辑） */
    private long currentDim() {
        CollectionInfo info = await(client().getCollectionInfoAsync(COLLECTION));
        return info.getConfig().getParams().getVectorsConfig().getParams().getSize();
    }

    private QdrantClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new QdrantClient(QdrantGrpcClient.newBuilder(
                            ragProperties.getVector().getQdrant().getHost(),
                            ragProperties.getVector().getQdrant().getPort(),
                            false
                    ).build());
                }
            }
        }
        return client;
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 记忆集合调用失败: " + e.getMessage(), e);
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float v : vector) {
            result.add(v);
        }
        return result;
    }

    /**
     * 记忆集合向量维度与配置不一致的配置错误。
     * 独立于 N1 的运行时故障降级：此类错误必须 fail-fast（对齐知识库侧 QdrantStore）。
     */
    static class DimensionMismatchException extends RuntimeException {
        DimensionMismatchException(String message) {
            super(message);
        }
    }
}