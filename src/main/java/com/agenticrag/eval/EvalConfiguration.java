package com.agenticrag.eval;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.rag.index.EmbeddingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
@Profile("eval")
public class EvalConfiguration {

    @Bean
    @Primary
    public EmbeddingClient evalEmbeddingClient(LlmProperties properties) {
        return new EmbeddingClient(properties) {
            @Override
            public float[] embed(String text) {
                return toVector(text);
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::toVector).toList();
            }

            @Override
            public String modelSlug() {
                return "eval-lexical";
            }

            private float[] toVector(String text) {
                String normalized = text == null ? "" : text.toLowerCase();
                float[] vector = new float[8];
                for (int i = 0; i < normalized.length(); i++) {
                    vector[i % vector.length] += normalized.charAt(i);
                }
                float norm = 0f;
                for (float value : vector) {
                    norm += value * value;
                }
                if (norm == 0f) {
                    return vector;
                }
                float scale = (float) (1.0 / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) {
                    vector[i] *= scale;
                }
                return vector;
            }
        };
    }
}
