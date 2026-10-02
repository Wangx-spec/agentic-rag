package com.agenticrag.dataanalysis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * demo 库只读数据源（第二数据源）：与主业务库物理隔离。
 * 仅当 rag.data.enabled=true 时装配；主数据源由 Spring Boot 自动配置，不受影响。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DataAnalysisProperties.class)
@ConditionalOnProperty(prefix = "rag.data", name = "enabled", havingValue = "true")
public class DataAnalysisConfig {

    @Bean("demoDataSource")
    public DataSource demoDataSource(DataAnalysisProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        log.info("已装配 demo 只读数据源: {}", properties.getUrl());
        return dataSource;
    }

    @Bean("demoJdbcTemplate")
    public JdbcTemplate demoJdbcTemplate(@Qualifier("demoDataSource") DataSource demoDataSource) {
        return new JdbcTemplate(demoDataSource);
    }
}
