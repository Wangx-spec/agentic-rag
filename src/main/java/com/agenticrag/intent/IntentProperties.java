package com.agenticrag.intent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


@Data
@ConfigurationProperties(prefix = "rag.intent")
public class IntentProperties {

    private boolean enabled = true;

    private String baseUrl = "";

    private String model = "";

    private String apiKey = "";

    private int timeoutSeconds = 5;

    private double confidenceThreshold = 0.6;
    
}
