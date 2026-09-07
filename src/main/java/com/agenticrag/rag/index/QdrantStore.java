package com.agenticrag.rag.index;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Qdrant 向量存储实现（官方 gRPC 客户端 io.qdrant:client，端口 6334）
 * <p>
 * 实现要点：
 * - 启动时确保 collection 存在（agentic_rag_chunks，Cosine 距离，HNSW m=16/ef_construct=100，维度读配置）
 * - point id = PG chunk id；payload = {content, docName, seq, documentId}，检索免回表
 * - 按 rag.vector.type=qdrant 时装配（条件装配）
 */
@ConditionalOnProperty(prefix = "rag.vector", name = "type", havingValue = "qdrant", matchIfMissing = true)
@Component
public class QdrantStore implements VectorStore {

    private final RagProperties ragProperties;
    private final EmbeddingClient embeddingClient;
    private volatile QdrantClient client;

    public QdrantStore(RagProperties ragProperties, EmbeddingClient embeddingClient) {
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
    }

    @PostConstruct
    public void init() {
        ensureCollection();
    }


    @Override
    public void saveChunks(long documentId, String docName, List<Chunk> chunks, List<float[]> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("chunks 与 vectors 数量不一致");
        }

        List<PointStruct> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);

            Map<String, Value> payload = new HashMap<>();
            payload.put("documentId", ValueFactory.value(documentId));
            payload.put("docName", ValueFactory.value(docName));
            payload.put("seq", ValueFactory.value(chunk.seq()));
            payload.put("content", ValueFactory.value(chunk.content()));

            points.add(PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setNum(chunk.id()).build())
                    .setVectors(Vectors.newBuilder()
                            .setVector(Vector.newBuilder().addAllData(toList(vector)).build())
                            .build())
                    .putAllPayload(payload)
                    .build());
        }

        await(client().upsertAsync(collectionName(), points));
    }

    @Override
    public List<VectorSearchResult> searchByVector(float[] queryVector, int topK) {
        SearchPoints request = SearchPoints.newBuilder()
                .setCollectionName(collectionName())
                .addAllVector(toList(queryVector))
                .setLimit(topK)
                .setParams(SearchParams.newBuilder()
                        .setHnswEf(ragProperties.getVector().getQdrant().getSearchEf())
                        .build())
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build())
                .build();

        List<ScoredPoint> results = await(client().searchAsync(request));
        List<VectorSearchResult> output = new ArrayList<>();

        for (ScoredPoint point : results) {
            Map<String, Value> payload = point.getPayloadMap();
            output.add(new VectorSearchResult(
                    point.getId().getNum(),
                    payload.get("documentId").getIntegerValue(),
                    (int) payload.get("seq").getIntegerValue(),
                    payload.get("content").getStringValue(),
                    payload.get("docName").getStringValue(),
                    point.getScore()
            ));
        }
        return output;
    }

    @Override
    public void deleteByDocumentId(long documentId) {
        Filter filter = Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("documentId")
                                .setMatch(Match.newBuilder().setInteger(documentId).build())
                                .build())
                        .build())
                .build();
        await(client().deleteAsync(collectionName(), filter));
    }

    /** 实际 collection 名：{prefix}_{modelSlug}_{dim}，按 embedding 模型/维度隔离 */
    private String collectionName() {
        return ragProperties.getVector().getQdrant()
                .resolveCollection(embeddingClient.modelSlug(), ragProperties.getEmbeddingDim());
    }

    private void ensureCollection() {
        String collection = collectionName();
        boolean exists = await(client().collectionExistsAsync(collection));
        if (exists) {
            long actual = currentDim(collection);
            int expected = ragProperties.getEmbeddingDim();
            if (actual != expected) {
                throw new IllegalStateException(
                        "Qdrant collection " + collection + " 向量维度为 " + actual
                                + "，但当前配置 rag.embedding-dim=" + expected
                                + "（embedding 模型 " + embeddingClient.modelSlug() + "）。"
                                + "请确认 embedding 模型与 rag.embedding-dim 配置一致。");
            }
            return;
        }

        CreateCollection request = CreateCollection.newBuilder()
                .setCollectionName(collection)
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
    }

    private long currentDim(String collection) {
        CollectionInfo info = await(client().getCollectionInfoAsync(collection));
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
            throw new IllegalStateException("Qdrant 调用失败: " + e.getMessage(), e);
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float v : vector) {
            result.add(v);
        }
        return result;
    }
}
