package com.lcl.myaiagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

// [LOCAL-ONLY-DISABLED] pgvector 专用数据源：全本地化改造（不使用向量数据库）暂时停用，恢复时取消注释
//@Configuration
@ConditionalOnProperty(value = "app.vector.enabled", havingValue = "true")
public class VectorDataSourceConfig {

    @Bean
    public JdbcTemplate vectorJdbcTemplate(
            @Value("${app.vector.datasource.jdbc-url}") String jdbcUrl,
            @Value("${app.vector.datasource.username}") String username,
            @Value("${app.vector.datasource.password}") String password,
            @Value("${app.vector.datasource.driver-class-name}") String driverClassName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        return new JdbcTemplate(dataSource);
    }
}
