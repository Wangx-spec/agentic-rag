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
    }
}
