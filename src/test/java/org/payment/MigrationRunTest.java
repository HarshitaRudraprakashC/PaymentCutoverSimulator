package org.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.payment.migration.MigrationReport;
import org.payment.migration.MigrationRunner;
import org.payment.repository.RolloutRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12: the whole migration, small. A real web server on a random port, because the runner
 * calls the API over HTTP. Batches of 40 instead of 1000, and the bug fires on every NEW
 * capture so the gate is guaranteed to have something to block on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cutover.resolver.enabled=false",
        "cutover.relay.enabled=false",
        "cutover.recon.projection-grace-seconds=3600",
        "cutover.migration.batch-size=40",
        "cutover.migration.merchants=60",
        "cutover.migration.bug-rate=1.0",
        "cutover.migration.timeout-rate=0.1"
})
class MigrationRunTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired MigrationRunner runner;
    @Autowired JdbcTemplate jdbc;
    @Autowired
    RolloutRepository rollout;

    /** Other test classes may have made NEW refuse captures; that history would trip the failure-rate gate. */
    @BeforeEach
    void cleanSlate() {
        jdbc.update("UPDATE payments SET capture_failures = 0 WHERE processor = 'NEW'");
    }

    /** The run ends at 100%. Other test classes expect 0% (everything on OLD), and they share this database. */
    @AfterEach
    void backToZero() {
        rollout.setNewPercent(0);
    }

    @Test
    void fullMigration_isBlockedByTheBug_thenReaches100_withNothingLostOrDoubled() {
        MigrationReport report = runner.run("http://localhost:" + port);

        assertThat(report.amountMismatchesFound()).isPositive();   // reconciliation found the planted bug
        assertThat(report.blockedByGate()).isTrue();               // and the gate stopped 25% -> 50%
        assertThat(report.inFlightOnNewAtRollback()).isPositive();
        assertThat(report.stranded()).isZero();                    // rollback stranded nothing
        assertThat(report.duplicateCharges()).isZero();
        assertThat(report.lostCharges()).isZero();
        assertThat(report.unbalancedJournals()).isZero();
        assertThat(report.kafkaDrift()).isZero();
        assertThat(report.finalPercent()).isEqualTo(100);
        assertThat(report.passed()).isTrue();
    }
}