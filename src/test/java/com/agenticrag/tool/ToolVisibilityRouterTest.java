package com.agenticrag.tool;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolVisibilityRouterTest {

    @Test
    void filtersSqlToolsOutForKbQa() {
        ToolRegistry registry = registry(
                tool("search_knowledge_base", ToolDomain.RETRIEVAL),
                tool("run_sql", ToolDomain.SQL),
                tool("calculator", ToolDomain.CALC)
        );

        ToolVisibilityRouter router = new ToolVisibilityRouter(registry, new RagProperties());

        var tools = router.visibleTools(Intent.KB_QA);

        assertTrue(tools.stream().anyMatch(t -> t.name().equals("search_knowledge_base")));
        assertFalse(tools.stream().anyMatch(t -> t.name().equals("run_sql")));
    }

    @Test
    void exposesSqlToolsForDataAnalysis() {
        ToolRegistry registry = registry(
                tool("search_knowledge_base", ToolDomain.RETRIEVAL),
                tool("run_sql", ToolDomain.SQL),
                tool("calculator", ToolDomain.CALC)
        );

        ToolVisibilityRouter router = new ToolVisibilityRouter(registry, new RagProperties());

        var tools = router.visibleTools(Intent.DATA_ANALYSIS);

        assertTrue(tools.stream().anyMatch(t -> t.name().equals("run_sql")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("calculator")));
        assertFalse(tools.stream().anyMatch(t -> t.name().equals("search_knowledge_base")));
    }

    @Test
    void unknownFailsOpenToAllTools() {
        ToolRegistry registry = registry(
                tool("search_knowledge_base", ToolDomain.RETRIEVAL),
                tool("run_sql", ToolDomain.SQL)
        );

        ToolVisibilityRouter router = new ToolVisibilityRouter(registry, new RagProperties());

        assertEquals(2, router.visibleTools(Intent.UNKNOWN).size());
    }

    @Test
    void disabledRoutingReturnsAllTools() {
        ToolRegistry registry = registry(
                tool("search_knowledge_base", ToolDomain.RETRIEVAL),
                tool("run_sql", ToolDomain.SQL)
        );
        RagProperties props = new RagProperties();
        props.getAgent().getRouting().setEnabled(false);

        ToolVisibilityRouter router = new ToolVisibilityRouter(registry, props);

        assertEquals(2, router.visibleTools(Intent.CHAT).size());
    }

    private static ToolRegistry registry(Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static Tool tool(String name, ToolDomain domain) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public String parametersSchema() { return "{}"; }
            @Override public Set<ToolDomain> domains() { return Set.of(domain); }
            @Override public String execute(Map<String, Object> arguments) { return "ok"; }
        };
    }
}
