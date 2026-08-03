package com.personal.baton.watch.adapter.out.persistence.monitoring;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shares one PostgreSQL container across the JUnit root context.
 *
 * <p>The inherited {@code @Testcontainers} annotation only checks Docker availability. Container
 * lifecycle is intentionally owned here, so the test support does not declare an {@code @Container}
 * field that would restart PostgreSQL for every test class.
 */
final class SharedPostgresExtension implements BeforeAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SharedPostgresExtension.class);
    private static final String RESOURCE_KEY = "shared-postgres";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("integration-test");

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot()
                .getStore(NAMESPACE)
                .computeIfAbsent(
                        RESOURCE_KEY,
                        ignored -> new SharedPostgresResource(),
                        SharedPostgresResource.class);
    }

    private static final class SharedPostgresResource implements AutoCloseable {

        private SharedPostgresResource() {
            POSTGRES.start();
        }

        @Override
        public void close() {
            POSTGRES.stop();
        }
    }
}
