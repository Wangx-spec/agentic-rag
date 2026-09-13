package com.agenticrag.tool.mcp;

import com.agenticrag.config.McpProperties;
import com.agenticrag.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class McpToolRegistrar {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private McpProperties mcpProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private McpSyncClient mcpClient;

    @PostConstruct
    public void init() {
        if (!mcpProperties.isEnabled()) {
            log.info("MCP 未启用，跳过远程工具注册");
            return;
        }

        try {
            McpClientTransport transport = createTransport();
            mcpClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            mcpClient.initialize();
            log.info("MCP 连接成功");

            McpSchema.ListToolsResult result = mcpClient.listTools();
            if (result == null || result.tools() == null || result.tools().isEmpty()) {
                log.info("MCP server 未提供任何工具");
                return;
            }

            for (McpSchema.Tool tool : result.tools()) {
                if (toolRegistry.find(tool.name()).isPresent()) {
                    log.info("MCP 工具 {} 与本地工具重名，跳过远程版本", tool.name());
                    continue;
                }
                McpToolAdapter adapter = new McpToolAdapter(mcpClient, tool, objectMapper);
                toolRegistry.register(adapter);
                log.info("已注册 MCP 远程工具: {}", tool.name());
            }
        } catch (Exception e) {
            log.warn("MCP server 连接失败，降级为仅本地工具: {}", e.getMessage());
            mcpClient = null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    private McpClientTransport createTransport() {
        String url = mcpProperties.getServer().getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("MCP server URL 未配置");
        }

        if (url.startsWith("stdio://")) {
            McpProperties.Stdio stdio = mcpProperties.getServer().getStdio();
            if (stdio.getCommand() == null || stdio.getCommand().isBlank()) {
                throw new IllegalStateException("MCP stdio command 未配置");
            }
            String[] args = stdio.getArgs() != null && !stdio.getArgs().isBlank()
                    ? stdio.getArgs().split("\\s+")
                    : new String[0];
            ServerParameters params = ServerParameters.builder(stdio.getCommand())
                    .args(args)
                    .build();
            return new StdioClientTransport(params, McpJsonDefaults.getMapper());
        }

        return HttpClientStreamableHttpTransport.builder(url).build();
    }
}
