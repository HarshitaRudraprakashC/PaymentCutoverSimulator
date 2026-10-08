package org.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build step 1 is done when this passes: the app starts against real Postgres and Kafka,
 * and Flyway has created the payments table.
 */
class ApplicationStartsTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appStartsAndFlywayCreatesPaymentsTable() {
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'payments'",
                Integer.class);

        assertThat(tables).isEqualTo(1);
    }
}
