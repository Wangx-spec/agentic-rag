package com.agenticrag.dataanalysis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * 数据分析（S3.1）数据源配置：demo 库只读数据源 + 主数据源显式声明。
 * 仅当 rag.data.enabled=true 时装配；enabled=false 时整类不装配，主数据源走 Spring Boot 自动配置，行为不变。
 *
 * <p>关键约束（多数据源 @Primary 隔离）：本类一旦定义任何 {@link DataSource} 类型 bean，
 * Spring Boot 的 {@code DataSourceAutoConfiguration} / {@code JdbcTemplateAutoConfiguration}
 * 会因 {@code @ConditionalOnMissingBean}（按类型匹配）而退让，不再创建主数据源与主 JdbcTemplate。
 * 因此必须在本类显式声明 {@code @Primary} 主数据源与主 JdbcTemplate，确保：
 * <ul>
 *   <li>裸注入 {@link JdbcTemplate} 的 RAG/Memory Repository（JdbcConversationMemory、
 *       UserProfileRepository、DocumentRepository、IngestService 等）仍连主库；</li>
 *   <li>{@code spring.sql.init} 的 {@code @ConditionalOnSingleCandidate(DataSource.class)} 命中
 *       {@code @Primary} 主数据源，初始化脚本只作用于主库；</li>
 *   <li>数据分析工具（RunSqlTool / ListTablesTool）通过 {@code @Qualifier("demoJdbcTemplate")} 连 demo 库。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DataAnalysisProperties.class)
@ConditionalOnProperty(prefix = "rag.data", name = "enabled", havingValue = "true")
public class DataAnalysisConfig {

    /** 主数据源（@Primary）：RAG/Memory Repository 裸注入 JdbcTemplate 时走这里，始终指向主库。
     *  用 DataSourceProperties.initializeDataSourceBuilder()（自动配置同款），正确完成 url→jdbcUrl 映射 */
    @Primary
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /** demo 库只读数据源（非 primary）：仅数据分析工具通过 @Qualifier("demoDataSource") 使用 */
    @Bean(name = "demoDataSource")
    public DataSource demoDataSource(DataAnalysisProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        log.info("已装配 demo 只读数据源: {}", properties.getUrl());
        return dataSource;
    }

    /** 主 JdbcTemplate（@Primary）：裸注入点拿到主库 */
    @Primary
    @Bean(name = "primaryJdbcTemplate")
    public JdbcTemplate primaryJdbcTemplate(@Qualifier("primaryDataSource") DataSource primaryDataSource) {
        return new JdbcTemplate(primaryDataSource);
    }

    /** demo JdbcTemplate：RunSqlTool / ListTablesTool 通过 @Qualifier("demoJdbcTemplate") 注入 */
    @Bean(name = "demoJdbcTemplate")
    public JdbcTemplate demoJdbcTemplate(@Qualifier("demoDataSource") DataSource demoDataSource) {
        return new JdbcTemplate(demoDataSource);
    }
}
