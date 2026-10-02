package com.agenticrag.dataanalysis;

import lombok.Data; 
import org.springframework.boot.context.properties.ConfigurationProperties; 

/**
 * 数据分析（S3.1）配置：demo 库连接 + 查询安全上限。
 * 仅本地开发启用，生产不配置（rag.data.enabled 默认 false）。
 */ 
@Data 
@ConfigurationProperties(prefix = "rag.data")
public class DataAnalysisProperties {

    /** 总开关：false 时数据源与工具均不装配，不影响现有 RAG 链路 */
    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private int queryTimeoutSeconds = 10;
    private int maxRows = 500;
    
}
