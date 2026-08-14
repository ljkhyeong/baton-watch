package com.personal.baton.watch.bootstrap;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 데이터베이스 연결 풀과 드라이버의 운영 자원·대기 상한을 검증한다. */
@Validated
@ConfigurationProperties("watch.database")
public record DatabaseRuntimeProperties(
        @Min(1) @Max(32) int maximumPoolSize,
        @Min(0) @Max(32) int minimumIdle,
        @Min(250) @Max(30_000) long connectionTimeoutMillis,
        @Min(250) @Max(30_000) long validationTimeoutMillis,
        @Min(10_000) @Max(1_800_000) long idleTimeoutMillis,
        @Min(30_000) @Max(3_600_000) long maxLifetimeMillis,
        @Min(30_000) @Max(1_800_000) long keepaliveTimeMillis,
        @Min(1) @Max(30_000) long initializationFailTimeoutMillis,
        @Min(1) @Max(30) int connectTimeoutSeconds,
        @Min(1) @Max(30) int loginTimeoutSeconds,
        @Min(1) @Max(120) int socketTimeoutSeconds,
        @Min(1) @Max(30) int cancelSignalTimeoutSeconds,
        boolean tcpKeepAlive) {

    public DatabaseRuntimeProperties {
        if (minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("minimumIdle must not exceed maximumPoolSize");
        }
        if (validationTimeoutMillis >= connectionTimeoutMillis) {
            throw new IllegalArgumentException("validationTimeoutMillis must be shorter than connectionTimeoutMillis");
        }
        if (minimumIdle < maximumPoolSize && idleTimeoutMillis + 1_000L > maxLifetimeMillis) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must leave at least 1000ms before maxLifetimeMillis");
        }
        if (keepaliveTimeMillis >= maxLifetimeMillis) {
            throw new IllegalArgumentException("keepaliveTimeMillis must be shorter than maxLifetimeMillis");
        }
    }
}
