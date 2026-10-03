package com.agenticrag.dataanalysis;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多数据源装配关系测试（对应 S3.1 集成约束的最高优先级项：不连错库）。
 *
 * 核心验证：当 rag.data.enabled=true 时，demo 数据源的存在会导致 Spring Boot 的
 * DataSourceAutoConfiguration / JdbcTemplateAutoConfiguration 退让（按类型 @ConditionalOnMissingBean），
 * 因此必须显式声明 @Primary 主数据源 + 主 JdbcTemplate，确保裸注入 JdbcTemplate 的
 * RAG/Memory Repository 仍连主库，而非误连 demo 库。
 */
class DataAnalysisConfigTest {

    private static final String MAIN_URL = "jdbc:postgresql://localhost:5432/agentic_rag";
    private static final String DEMO_URL = "jdbc:postgresql://localhost:5432/agentic_rag_demo";

    private final ApplicationContextRunner enabledRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    DataSourceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + MAIN_URL,
                    "spring.datasource.username=postgres",
                    "spring.datasource.password=postgres",
                    "rag.data.enabled=true",
                    "rag.data.url=" + DEMO_URL,
                    "rag.data.username=demo_readonly",
                    "rag.data.password=demo_readonly")
            .withUserConfiguration(DataAnalysisConfig.class);

    @Test
    void enabledTrue_mainDataSourcePointsToMainDb() {
        enabledRunner.run(context -> {
            HikariDataSource primary = (HikariDataSource) context.getBean("dataSource");
            assertThat(primary.getJdbcUrl()).isEqualTo(MAIN_URL);
        });
    }

    @Test
    void enabledTrue_demoDataSourcePointsToDemoDb() {
        enabledRunner.run(context -> {
            DriverManagerDataSource demo = (DriverManagerDataSource) context.getBean("demoDataSource");
            assertThat(demo.getUrl()).isEqualTo(DEMO_URL);
        });
    }

    @Test
    void enabledTrue_bareJdbcTemplateInjectionResolvesToMainNotDemo() {
        enabledRunner.run(context -> {
            JdbcTemplate main = context.getBean("jdbcTemplate", JdbcTemplate.class);
            JdbcTemplate demo = context.getBean("demoJdbcTemplate", JdbcTemplate.class);
            assertThat(main).isNotSameAs(demo);

            // 裸注入（未加 @Qualifier 时）必须拿到主库 JdbcTemplate（@Primary 兜底）
            JdbcTemplate injected = context.getBean(JdbcTemplate.class);
            assertThat(injected).isSameAs(main);
            assertThat(injected).isNotSameAs(demo);
        });
    }

    @Test
    void enabledFalse_demoBeansNotAssembled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + MAIN_URL,
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=postgres",
                        "rag.data.enabled=false")
                .withUserConfiguration(DataAnalysisConfig.class)
                .run(context -> {
                    // demo 数据源相关 bean 完全不装配
                    assertThat(context).doesNotHaveBean("demoDataSource");
                    assertThat(context).doesNotHaveBean("demoJdbcTemplate");
                    // 主库数据源由 Spring Boot 自动配置提供（bean 名 dataSource），仅此一个 DataSource
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasBean("dataSource");
                });
    }
}
