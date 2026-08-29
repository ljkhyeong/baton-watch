package com.personal.baton.watch.bootstrap;

import static org.postgresql.PGProperty.CANCEL_SIGNAL_TIMEOUT;
import static org.postgresql.PGProperty.CONNECT_TIMEOUT;
import static org.postgresql.PGProperty.LOGIN_TIMEOUT;
import static org.postgresql.PGProperty.SOCKET_TIMEOUT;
import static org.postgresql.PGProperty.TCP_KEEP_ALIVE;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Properties;
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
        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        connectionDetailsProvider.ifAvailable(connectionDetails -> {
            dataSource.setJdbcUrl(connectionDetails.getJdbcUrl());
            dataSource.setUsername(connectionDetails.getUsername());
            dataSource.setPassword(connectionDetails.getPassword());
            if (connectionDetails.getDriverClassName() != null) {
                dataSource.setDriverClassName(connectionDetails.getDriverClassName());
            }
        });
        requireSafeJdbcUrl(dataSource.getJdbcUrl());
        dataSource.setMaximumPoolSize(runtime.maximumPoolSize());
        dataSource.setMinimumIdle(runtime.minimumIdle());
        dataSource.setConnectionTimeout(runtime.connectionTimeoutMillis());
        dataSource.setValidationTimeout(runtime.validationTimeoutMillis());
        dataSource.setIdleTimeout(runtime.idleTimeoutMillis());
        dataSource.setMaxLifetime(runtime.maxLifetimeMillis());
        dataSource.setKeepaliveTime(runtime.keepaliveTimeMillis());
        dataSource.setInitializationFailTimeout(runtime.initializationFailTimeoutMillis());
        Properties driverProperties = dataSource.getDataSourceProperties();
        CONNECT_TIMEOUT.set(driverProperties, runtime.connectTimeoutSeconds());
        LOGIN_TIMEOUT.set(driverProperties, runtime.loginTimeoutSeconds());
        SOCKET_TIMEOUT.set(driverProperties, runtime.socketTimeoutSeconds());
        CANCEL_SIGNAL_TIMEOUT.set(driverProperties, runtime.cancelSignalTimeoutSeconds());
        TCP_KEEP_ALIVE.set(driverProperties, runtime.tcpKeepAlive());
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
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("데이터베이스 JDBC URL 형식이 올바르지 않습니다");
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.equals("loggerLevel=OFF")) {
            throw new IllegalArgumentException("데이터베이스 JDBC URL에 허용되지 않는 쿼리 매개변수가 있습니다");
        }
    }
}
