package com.agenticrag.tool;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.QueryUnderstanding;
import com.agenticrag.llm.dto.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * S3.2：按查询理解意图过滤 Agent 当次可见工具集合。
 * <p>
 * 路由失败或意图未知时 fail-open，回退到全量已注册工具，保持主链路可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolVisibilityRouter {

    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;

    public List<ToolSchema> visibleTools(Optional<QueryUnderstanding> understanding) {
        return visibleTools(understanding.map(QueryUnderstanding::intent).orElse(Intent.UNKNOWN));
    }

    public List<ToolSchema> visibleTools(Intent intent) {
        try {
            if (!routingEnabled()) {
                return allToolSchemas();
            }

            Set<ToolDomain> allowed = allowedDomains(intent);
            if (allowed == null) {
                return allToolSchemas();
            }
            if (allowed.isEmpty()) {
                return List.of();
            }

            List<Tool> filtered = toolRegistry.all().values().stream()
                    .filter(tool -> visible(tool, allowed))
                    .toList();
            if (filtered.isEmpty() && !toolRegistry.all().isEmpty()) {
                log.warn("工具白名单过滤结果为空，fail-open 回退全量工具: intent={}", intent);
                return allToolSchemas();
            }
            return filtered.stream().map(this::toSchema).toList();
        } catch (Exception e) {
            log.warn("工具白名单路由失败，fail-open 回退全量工具", e);
            return allToolSchemas();
        }
    }

    public boolean allowsDomain(Optional<QueryUnderstanding> understanding, ToolDomain domain) {
        if (!routingEnabled()) {
            return true;
        }
        Set<ToolDomain> allowed = allowedDomains(understanding.map(QueryUnderstanding::intent).orElse(Intent.UNKNOWN));
        return allowed == null || allowed.contains(domain);
    }

    private boolean visible(Tool tool, Set<ToolDomain> allowed) {
        Set<ToolDomain> domains = tool.domains();
        if (domains == null || domains.isEmpty()) {
            return false;
        }
        if (domains.contains(ToolDomain.MCP) && domains.size() == 1) {
            return includeMcpTools();
        }
        return domains.stream().anyMatch(allowed::contains)
                || (includeMcpTools() && domains.contains(ToolDomain.MCP));
    }

    private Set<ToolDomain> allowedDomains(Intent intent) {
        if (intent == null || intent == Intent.UNKNOWN) {
            return null;
        }
        return switch (intent) {
            case CHAT, OFF_TOPIC -> EnumSet.noneOf(ToolDomain.class);
            case KB_QA -> EnumSet.of(ToolDomain.RETRIEVAL, ToolDomain.DOCUMENT, ToolDomain.MEMORY);
            case DATA_ANALYSIS -> EnumSet.of(ToolDomain.SQL, ToolDomain.CALC);
            case TOOL_TASK -> EnumSet.of(ToolDomain.CALC, ToolDomain.RETRIEVAL, ToolDomain.MEMORY);
            case MULTI_TASK -> EnumSet.of(ToolDomain.RETRIEVAL, ToolDomain.DOCUMENT, ToolDomain.MEMORY,
                    ToolDomain.SQL, ToolDomain.CALC);
            case UNKNOWN -> null;
        };
    }

    private List<ToolSchema> allToolSchemas() {
        return toolRegistry.all().values().stream().map(this::toSchema).toList();
    }

    private ToolSchema toSchema(Tool tool) {
        return new ToolSchema(tool.name(), tool.description(), tool.parametersSchema());
    }

    private boolean routingEnabled() {
        return ragProperties.getAgent() == null
                || ragProperties.getAgent().getRouting() == null
                || ragProperties.getAgent().getRouting().isEnabled();
    }

    private boolean includeMcpTools() {
        return ragProperties.getAgent() == null
                || ragProperties.getAgent().getRouting() == null
                || ragProperties.getAgent().getRouting().isIncludeMcpTools();
    }
}
