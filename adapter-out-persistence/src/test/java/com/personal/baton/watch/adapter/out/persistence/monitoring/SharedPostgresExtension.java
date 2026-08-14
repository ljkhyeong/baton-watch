package com.personal.baton.watch.adapter.out.persistence.monitoring;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 하나의 PostgreSQL 컨테이너를 JUnit 루트 컨텍스트 전체에서 공유한다.
 *
 * <p>Docker를 사용할 수 없으면 컨테이너 시작이 실패한다. 컨테이너 생명주기는 이곳에서 명시적으로
 * 소유하므로 테스트 지원 코드는 각 테스트 클래스마다
 * PostgreSQL을 다시 시작하게 되는 {@code @Container} 필드를 선언하지 않는다.
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
