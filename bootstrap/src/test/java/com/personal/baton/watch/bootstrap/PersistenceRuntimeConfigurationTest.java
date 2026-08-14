package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PersistenceRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(DatabaseRuntimeConfiguration.class)
            .withPropertyValues("spring.datasource.password=test-password");

    @Test
    void appliesBoundedHikariAndPostgresDriverDefaultsFromProductionConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);

            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(8);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(2);
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
            assertThat(dataSource.getValidationTimeout()).isEqualTo(1_000L);
            assertThat(dataSource.getIdleTimeout()).isEqualTo(600_000L);
            assertThat(dataSource.getMaxLifetime()).isEqualTo(1_800_000L);
            assertThat(dataSource.getKeepaliveTime()).isEqualTo(120_000L);
            assertThat(dataSource.getInitializationFailTimeout()).isEqualTo(5_000L);

            Properties driver = dataSource.getDataSourceProperties();
            assertThat(driver)
                    .containsEntry("connectTimeout", "3")
                    .containsEntry("loginTimeout", "5")
                    .containsEntry("socketTimeout", "10")
                    .containsEntry("cancelSignalTimeout", "3")
                    .containsEntry("tcpKeepAlive", "true");
        });
    }

    @Test
    void appliesValidDatabaseRuntimeOverrides() {
        contextRunner
                .withPropertyValues(
                        "watch.database.maximum-pool-size=12",
                        "watch.database.minimum-idle=3",
                        "watch.database.connection-timeout-millis=4000",
                        "watch.database.validation-timeout-millis=1500",
                        "watch.database.idle-timeout-millis=59000",
                        "watch.database.max-lifetime-millis=60000",
                        "watch.database.keepalive-time-millis=30000",
                        "watch.database.socket-timeout-seconds=20")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(12);
                    assertThat(dataSource.getMinimumIdle()).isEqualTo(3);
                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(4_000L);
                    assertThat(dataSource.getValidationTimeout()).isEqualTo(1_500L);
                    assertThat(dataSource.getIdleTimeout()).isEqualTo(59_000L);
                    assertThat(dataSource.getMaxLifetime()).isEqualTo(60_000L);
                    assertThat(dataSource.getDataSourceProperties())
                            .containsEntry("socketTimeout", "20");
                });
    }

    @Test
    void rejectsDatabaseSettingsThatDisableOrUnboundRuntimeLimits() {
        assertStartupFailure("watch.database.connection-timeout-millis=0", "connectionTimeoutMillis");
        assertStartupFailure("watch.database.socket-timeout-seconds=0", "socketTimeoutSeconds");
        assertStartupFailure("watch.database.maximum-pool-size=33", "maximumPoolSize");
        assertStartupFailure("watch.database.minimum-idle=9", "minimumIdle");
        assertStartupFailure("watch.database.validation-timeout-millis=3000", "validationTimeoutMillis");
        assertStartupFailure("watch.database.keepalive-time-millis=1800000", "keepaliveTimeMillis");
        contextRunner
                .withPropertyValues(
                        "watch.database.idle-timeout-millis=59001",
                        "watch.database.max-lifetime-millis=60000",
                        "watch.database.keepalive-time-millis=30000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("idleTimeoutMillis");
                });
    }

    @Test
    void standardBootHikariPropertiesCannotBypassWatchRuntimeLimits() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.hikari.maximum-pool-size=64",
                        "spring.datasource.hikari.connection-timeout=0",
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "spring.datasource.hikari.data-source-properties.socketTimeout=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(8);
                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
                    assertThat(dataSource.getInitializationFailTimeout()).isEqualTo(5_000L);
                    assertThat(dataSource.getDataSourceProperties())
                            .containsEntry("socketTimeout", "10");
                });
    }

    @Test
    void rejectsJdbcUrlQueryParametersThatCanOverridePostgresRuntimeLimits() {
        assertJdbcUrlFailure(
                "jdbc:postgresql://localhost:5432/baton_watch?socketTimeout=0",
                "JDBC URL에 허용되지 않는 쿼리 매개변수가 있습니다");
        assertJdbcUrlFailure(
                "jdbc:postgresql:baton_watch?socketTimeout=0",
                "JDBC URL 형식이 올바르지 않습니다");
    }

    private void assertStartupFailure(String property, String fieldName) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(fieldName);
        });
    }

    private void assertJdbcUrlFailure(String jdbcUrl, String message) {
        contextRunner.withPropertyValues("spring.datasource.url=" + jdbcUrl).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(message);
        });
    }

}
