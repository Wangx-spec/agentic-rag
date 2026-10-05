package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.QueryUnderstanding;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * S3.2：把查询理解拆出的子问题转换为轻量执行规划提示。
 */
@Component
public class SubQueryPlanner {

    private final RagProperties ragProperties;

    public SubQueryPlanner(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public Optional<String> buildPlanBlock(Optional<QueryUnderstanding> understanding) {
        if (!planningEnabled() || understanding.isEmpty()) {
            return Optional.empty();
        }
        QueryUnderstanding value = understanding.get();
        if ((value.intent() != Intent.MULTI_TASK && value.intent() != Intent.DATA_ANALYSIS)
                || value.subQueries().isEmpty()) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder("分析规划：请按以下子问题逐步调用工具，并在最终回答中引用证据编号 [n]。\n");
        for (int i = 0; i < value.subQueries().size(); i++) {
            String subQuery = value.subQueries().get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(subQuery)
                    .append("（建议数据源：")
                    .append(recommendSource(subQuery))
                    .append("）\n");
        }
        sb.append("如 SQL 取数结果中出现平台、品类、商品等维度值，可继续用这些维度检索知识库评论或说明。");
        return Optional.of(sb.toString());
    }

    private String recommendSource(String query) {
        boolean sql = containsAny(query, "统计", "排名", "多少", "总量", "销售额", "订单", "销量", "占比", "趋势", "对比");
        boolean knowledge = containsAny(query, "评论", "评价", "反馈", "怎么说", "原因", "观点", "文本", "用户说");
        if (sql && knowledge) {
            return "SQL + 知识库";
        }
        if (sql) {
            return "SQL";
        }
        if (knowledge) {
            return "知识库";
        }
        return "知识库";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean planningEnabled() {
        return ragProperties.getAgent() == null
                || ragProperties.getAgent().getPlanning() == null
                || ragProperties.getAgent().getPlanning().isEnabled();
    }
}
