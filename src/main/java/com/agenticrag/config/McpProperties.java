package com.agenticrag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private boolean enabled = false;

    private Server server = new Server();

    @Data
    public static class Server {

        private String url;

        private Stdio stdio = new Stdio();
    }

    @Data
    public static class Stdio {

        private String command;

        private String args;
    }
}