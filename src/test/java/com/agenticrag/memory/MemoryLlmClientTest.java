package com.agenticrag.memory;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave A 自检对应：覆盖 N2「空回退主 LLM / 部分覆盖 / 全量覆盖 / timeout=0 不覆盖」四条路径。
 * 纯离线：仅测 effectiveProperties 的纯函数合成，不发真实网络请求。
 */
class MemoryLlmClientTest {

    private LlmProperties main() {
        LlmProperties main = new LlmProperties();
        main.setBaseUrl("https://main.example/v1");
        main.setApiKey("main-key");
        main.setChatModel("main-model");
        main.setEmbeddingModel("main-embed");
        main.setTimeoutSeconds(60);
        return main;
    }

    private MemoryProperties memory(MemoryProperties.Llm llm) {
        MemoryProperties props = new MemoryProperties();
        props.setLlm(llm);
        return props;
    }

    @Test
    void 空小模型配置_网关模型回退主Llm_超时用记忆默认值() {
        MemoryLlmClient client = new MemoryLlmClient(memory(new MemoryProperties.Llm()), main());
        LlmProperties effective = client.effectiveProperties();
        assertThat(effective.getBaseUrl()).isEqualTo("https://main.example/v1");
        assertThat(effective.getChatModel()).isEqualTo("main-model");
        assertThat(effective.getApiKey()).isEqualTo("main-key");
        // timeout 独立于 hasDedicated 判定：默认 15s 对记忆任务做超时保护，不共用主 LLM 的 60s
        assertThat(effective.getTimeoutSeconds()).isEqualTo(15);
    }

    @Test
    void 全量专用配置_逐项覆盖主Llm() {
        MemoryProperties.Llm llm = new MemoryProperties.Llm();
        llm.setBaseUrl("https://cheap.example/v1");
        llm.setModel("cheap-model");
        llm.setApiKey("cheap-key");
        llm.setTimeoutSeconds(15);

        MemoryLlmClient client = new MemoryLlmClient(memory(llm), main());
        LlmProperties effective = client.effectiveProperties();
        assertThat(effective.getBaseUrl()).isEqualTo("https://cheap.example/v1");
        assertThat(effective.getChatModel()).isEqualTo("cheap-model");
        assertThat(effective.getApiKey()).isEqualTo("cheap-key");
        assertThat(effective.getTimeoutSeconds()).isEqualTo(15);
    }

    @Test
    void 部分覆盖_仅配模型_网关与密钥沿用主配置() {
        MemoryProperties.Llm llm = new MemoryProperties.Llm();
        llm.setModel("cheap-model");

        MemoryLlmClient client = new MemoryLlmClient(memory(llm), main());
        LlmProperties effective = client.effectiveProperties();
        assertThat(effective.getChatModel()).isEqualTo("cheap-model");
        assertThat(effective.getBaseUrl()).isEqualTo("https://main.example/v1");
        assertThat(effective.getApiKey()).isEqualTo("main-key");
    }

    @Test
    void timeout为零_不覆盖主超时() {
        MemoryProperties.Llm llm = new MemoryProperties.Llm();
        llm.setTimeoutSeconds(0);

        MemoryLlmClient client = new MemoryLlmClient(memory(llm), main());
        assertThat(client.effectiveProperties().getTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    void defaults_绑定缺省值与plan口径一致() {
        MemoryProperties props = new MemoryProperties();
        assertThat(props.getKeepTurns()).isEqualTo(8);
        assertThat(props.getSummary().isEnabled()).isTrue();
        assertThat(props.getSummary().getMaxChars()).isEqualTo(300);
        assertThat(props.getExtract().getIntervalTurns()).isEqualTo(5);
        assertThat(props.getProfileMaxEntries()).isEqualTo(50);
        assertThat(props.getLtmMaxPerUser()).isEqualTo(200);
        assertThat(props.getLtmSearchTopk()).isEqualTo(3);
        assertThat(props.getLlm().getTimeoutSeconds()).isEqualTo(15);
        assertThat(props.getLlm().hasDedicated()).isFalse();
    }

    @Test
    void chat_子类覆盖注入_fake链路可测() {
        MemoryLlmClient fake = new MemoryLlmClient(memory(new MemoryProperties.Llm()), main()) {
            @Override
            public String chat(List<ChatMessage> messages) {
                return "fake-summary";
            }
        };
        assertThat(fake.chat(List.of(ChatMessage.system("压缩以下对话")))).isEqualTo("fake-summary");
    }
}
