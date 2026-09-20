package com.agenticrag.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    private boolean llmJudge = false;

    private String reportPath = "./eval-report.md";

    /**
     * 评测集开关：internal（内置 eval-set，默认）| enterpriserag（EnterpriseRAG-Bench）
     */
    private String bench = "internal";

    private EnterpriseRag enterpriseRag = new EnterpriseRag();

    @Data
    public static class EnterpriseRag {

        /** Confluence 语料目录（转换产物 *.md，文件名=dsid） */
        private String corpusDir = "./data/enterpriserag-md";

        /** 官方判分格式 answers 输出路径（逐题追加写，jsonl） */
        private String answersPath = "./eval-answers/answers.jsonl";

        /** 内部指标报告输出路径 */
        private String reportPath = "./enterpriserag-report.md";

        /**
         * 作答模式：rag（默认，单次检索生成）| agent（ReAct 工具循环）| multi-agent | auto。
         * 见 {@link com.agenticrag.service.ChatMode}
         */
        private String chatMode = "rag";

        /**
         * 题目文件路径（Phase 5 扰动集用）：设置后从文件系统读该 jsonl，
         * 为空时回退 classpath 内置资源 eval/datasets/enterprise-rag-64.jsonl
         */
        private String questionsPath = "";
    }
}
