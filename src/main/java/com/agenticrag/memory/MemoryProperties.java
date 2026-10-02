package com.agenticrag.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M9 记忆配置（前缀 rag.memory），绑定 application.yaml 中 rag.memory.* 全量键。
 * <p>
 * type 三态（memory | redis | jdbc）仅作为 @ConditionalOnProperty 装配开关；
 * keepTurns 为原文保留窗口（轮），窗口外历史由 SummaryService 压缩为滚动摘要。
 */
@Data
@ConfigurationProperties(prefix = "rag.memory")
public class MemoryProperties {

    /** 实现类型：memory | redis | jdbc */
    private String type = "memory";

    /** 原文保留窗口（轮），发送给 LLM 的消息数上限 ≈ keepTurns*2+2（含摘要与当前轮） */
    private int keepTurns = 8;

    /** N4：实体记忆单用户条目上限，超限淘汰最旧 key */
    private int profileMaxEntries = 50;

    /** N4：长期记忆单用户保留上限，超限淘汰最旧条目 */
    private int ltmMaxPerUser = 200;

    /** 读阶段长期记忆语义检索注入条数 */
    private int ltmSearchTopk = 3;

    /** 摘要压缩配置 */
    private Summary summary = new Summary();

    /** 周期批量提取配置 */
    private Extract extract = new Extract();

    /** N2：摘要+提取共用小模型，留空回退主 LLM */
    private Llm llm = new Llm();
    
    @Data
    public static class Summary {
        /** 滑动摘要总开关（关闭后退化为你纯窗口截断，token 上限不再受控） */
        private boolean enabled = true;
        /** 摘要滚动字符上限，防历次归并累积膨胀 */
        private int maxChars = 300;
    }

    @Data
    public static class Extract {
        /** 周期批量写回总开关（关闭后请求侧不再累计轮数） */
        private boolean enabled = true;

        /** 每 N 轮触发一次小模型「判断+提取」 */
        private int intervalTurns = 5;

        /** 脏会话扫描间隔（毫秒），提取执行不占用请求线程 */
        private long scanIntervalMs = 30000;
    }

    @Data
    public static class Llm {
        private String baseUrl = "";
        private String model = "";
        private String apiKey = "";
        private int timeoutSeconds = 15;

        public boolean hasDedicated() {
            return !baseUrl.isBlank() || !model.isBlank() || !apiKey.isBlank();
        }
    }
}
