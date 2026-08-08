package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.persistence.monitoring.PostgresTransactionOperations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Assembles the bounded transaction policy shared by every PostgreSQL adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
class PersistenceTransactionConfiguration {

    @Bean
    TransactionOperations persistenceTransactionOperations(
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate,
            PersistenceProperties properties) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setName("baton-watch-persistence");
        transactions.setTimeout(properties.transactionTimeoutSeconds());
        return new PostgresTransactionOperations(
                jdbcTemplate, transactions, properties.lockTimeout());
    }
}
