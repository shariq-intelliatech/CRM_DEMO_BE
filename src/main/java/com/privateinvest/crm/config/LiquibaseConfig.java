package com.privateinvest.crm.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the Liquibase changelog at start-up. Spring Boot 4 keeps Liquibase auto-configuration in a separate module,
 * so the bean is declared here.
 * <p>
 * Contexts: changesets marked {@code context:dev} (dev users, sample Persons and Entities) run only when the
 * contexts include {@code dev}. Production sets {@code spring.liquibase.contexts=default}.
 */
@Configuration
@ConditionalOnProperty(name = "spring.liquibase.enabled", havingValue = "true", matchIfMissing = true)
public class LiquibaseConfig {

    @Bean
    SpringLiquibase liquibase(DataSource dataSource,
                              @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog,
                              @Value("${spring.liquibase.contexts:default,dev}") String contexts,
                              @Value("${spring.liquibase.drop-first:false}") boolean dropFirst) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setContexts(contexts);
        liquibase.setDropFirst(dropFirst);
        return liquibase;
    }
}
