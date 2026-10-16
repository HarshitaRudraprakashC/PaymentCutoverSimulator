package org.payment;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Every integration test extends this class.
 * The containers start ONCE for the whole test run and are shared by all test classes.
 * (No @Testcontainers / @Container: those would stop them after each class, while Spring
 * keeps reusing the app it built for the first class.)
 * Testcontainers removes them automatically when the test run ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cutover.resolver.enabled=false",
        "cutover.relay.enabled=false",
        "cutover.recon.projection-grace-seconds=3600",
        "cutover.migration.batch-size=40",
        "cutover.migration.merchants=60",
        "cutover.migration.bug-rate=1.0",
        "cutover.migration.timeout-rate=0.1",
        "cutover.simulation.payments=150",
        "cutover.simulation.timeout-rate=0.3"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
