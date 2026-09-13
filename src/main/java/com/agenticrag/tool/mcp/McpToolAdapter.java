package com.agenticrag.tool.mcp;

import com.agenticrag.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.stream.Collectors;

public class McpToolAdapter implements Tool {

    private final McpSyncClient client;
    private final McpSchema.Tool remoteTool;
    private final ObjectMapper objectMapper;

    public McpToolAdapter(McpSyncClient client, McpSchema.Tool remoteTool, ObjectMapper objectMapper) {
        this.client = client;
        this.remoteTool = remoteTool;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return remoteTool.name();
    }

    @Override
    public String description() {
        return remoteTool.description();
    }

    @Override
    public String parametersSchema() {
        try {
            return objectMapper.writeValueAsString(remoteTool.inputSchema());
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder(remoteTool.name())
                        .arguments(arguments)
                        .build()
        );
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}