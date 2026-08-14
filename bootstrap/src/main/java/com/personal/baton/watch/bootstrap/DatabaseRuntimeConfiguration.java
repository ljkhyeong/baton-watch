package com.personal.baton.watch.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Boot가 관리하는 연결 정보와 WATCH가 검증한 데이터베이스 실행 상한을 조립한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseRuntimeProperties.class)
class DatabaseRuntimeConfiguration {

    @Bean
    HikariDataSource dataSource(
            DataSourceProperties dataSourceProperties,
            ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider,
            DatabaseRuntimeProperties runtime) {
        JdbcConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
        String jdbcUrl = connectionDetails != null
                ? connectionDetails.getJdbcUrl()
                : dataSourceProperties.determineUrl();
        requireSafeJdbcUrl(jdbcUrl);
        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(connectionDetails != null
                ? connectionDetails.getUsername()
                : dataSourceProperties.determineUsername());
        dataSource.setPassword(connectionDetails != null
                ? connectionDetails.getPassword()
                : dataSourceProperties.determinePassword());
        String driverClassName = connectionDetails != null
                ? connectionDetails.getDriverClassName()
                : dataSourceProperties.determineDriverClassName();
        if (driverClassName != null) {
            dataSource.setDriverClassName(driverClassName);
        }
        dataSource.setMaximumPoolSize(runtime.maximumPoolSize());
        dataSource.setMinimumIdle(runtime.minimumIdle());
        dataSource.setConnectionTimeout(runtime.connectionTimeoutMillis());
        dataSource.setValidationTimeout(runtime.validationTimeoutMillis());
        dataSource.setIdleTimeout(runtime.idleTimeoutMillis());
        dataSource.setMaxLifetime(runtime.maxLifetimeMillis());
        dataSource.setKeepaliveTime(runtime.keepaliveTimeMillis());
        dataSource.setInitializationFailTimeout(runtime.initializationFailTimeoutMillis());
        dataSource.addDataSourceProperty("connectTimeout", Integer.toString(runtime.connectTimeoutSeconds()));
        dataSource.addDataSourceProperty("loginTimeout", Integer.toString(runtime.loginTimeoutSeconds()));
        dataSource.addDataSourceProperty("socketTimeout", Integer.toString(runtime.socketTimeoutSeconds()));
        dataSource.addDataSourceProperty(
                "cancelSignalTimeout", Integer.toString(runtime.cancelSignalTimeoutSeconds()));
        dataSource.addDataSourceProperty("tcpKeepAlive", Boolean.toString(runtime.tcpKeepAlive()));
        return dataSource;
    }

    private static void requireSafeJdbcUrl(String jdbcUrl) {
        URI uri;
        try {
            if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException();
            }
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("데이터베이스 JDBC URL 형식이 올바르지 않습니다");
        }
        if (!"postgresql".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("데이터베이스 JDBC URL 형식이 올바르지 않습니다");
        }
        int queryIndex = jdbcUrl.indexOf('?');
        if (queryIndex >= 0 && !jdbcUrl.substring(queryIndex + 1).equals("loggerLevel=OFF")) {
            throw new IllegalArgumentException("데이터베이스 JDBC URL에 허용되지 않는 쿼리 매개변수가 있습니다");
        }
    }
}
