package com.binocheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application against a real Postgres (Flyway migrations + Hibernate schema validation).
 * Runs whenever Docker is available and is skipped otherwise, so `mvn test` works on any machine.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BinocheckApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
    }
}
