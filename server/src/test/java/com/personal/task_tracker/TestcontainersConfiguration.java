package com.personal.task_tracker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL container shared by the integration tests.
 * Declared as a bean so that every test class importing this configuration with the same context
 * reuses the cached Spring context, and therefore the same container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}
}
