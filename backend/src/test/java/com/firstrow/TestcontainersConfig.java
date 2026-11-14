package com.firstrow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL 16 in Docker for tests (Testcontainers).
 * {@code @ServiceConnection} makes Spring Boot point the DataSource at this container automatically,
 * so tests run against the same database engine as production instead of an in-memory fake.
 * Spring caches the test context, so one container is shared by all integration tests in a run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
