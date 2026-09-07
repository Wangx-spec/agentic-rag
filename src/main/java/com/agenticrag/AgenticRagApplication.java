package com.agenticrag;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, RagProperties.class})
public class AgenticRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticRagApplication.class, args);
    }
}