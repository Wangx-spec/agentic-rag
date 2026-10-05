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

    private Trace trace = new Trace();

    private Da da = new Da();

    @Data
    public static class Trace {

        /** S3.3 轨迹采集开关：默认关闭，仅评测脚本显式开启。 */
        private boolean enabled = false;

        /** 工具参数/结果摘要最大字符数。 */
        private int summaryMaxChars = 500;
    }

    @Data
    public static class Da {

        /** S3.3 数据分析评测集路径（入库版本化）。 */
        private String questionsFile = "eval/datasets/da-questions-v1.jsonl";

        /** 数据分析评测答案与报告输出目录。 */
        private String roundsDir = "eval-answers/rounds-da";

        /** run | judge */
        private String action = "run";

        /** 本次评测运行标识。 */
        private String runId = "da-smoke";

        /** 冒烟时可限制题数；0 表示全量。 */
        private int limit = 0;
    }

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
