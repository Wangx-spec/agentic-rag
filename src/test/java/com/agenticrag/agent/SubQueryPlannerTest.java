package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.QueryUnderstanding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubQueryPlannerTest {

    @Test
    void buildsPlanForDataAnalysisSubQueries() {
        SubQueryPlanner planner = new SubQueryPlanner(new RagProperties());
        QueryUnderstanding understanding = new QueryUnderstanding(
                Intent.DATA_ANALYSIS,
                0.9,
                "分析销量下滑原因",
                List.of("各品类销售额排名是多少", "用户评论怎么说")
        );

        String plan = planner.buildPlanBlock(Optional.of(understanding)).orElseThrow();

        assertTrue(plan.contains("分析规划"));
        assertTrue(plan.contains("建议数据源：SQL"));
        assertTrue(plan.contains("建议数据源：知识库"));
        assertTrue(plan.contains("引用证据编号 [n]"));
    }

    @Test
    void skipsWhenPlanningDisabled() {
        RagProperties props = new RagProperties();
        props.getAgent().getPlanning().setEnabled(false);
        SubQueryPlanner planner = new SubQueryPlanner(props);
        QueryUnderstanding understanding = new QueryUnderstanding(
                Intent.DATA_ANALYSIS,
                0.9,
                "分析销量",
                List.of("各品类销售额排名是多少")
        );

        assertTrue(planner.buildPlanBlock(Optional.of(understanding)).isEmpty());
    }
}
