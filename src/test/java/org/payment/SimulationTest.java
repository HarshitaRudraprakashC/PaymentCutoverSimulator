package org.payment;

import org.junit.jupiter.api.Test;
import org.payment.simulation.Simulation;
import org.payment.simulation.Simulation.RunResult;
import org.payment.simulation.Simulation.SimulationReport;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S11 at scale: the same payments and timeouts, captured safely and naively.
 * Small (150 each) with a high timeout rate (30%) so the naive run is certain to double charge.
 */
class SimulationTest extends IntegrationTestBase {

    @Autowired Simulation simulation;

    @Test
    void safeLosesAndDoublesNothing_naiveDoubleCharges_andReconciliationCatchesIt() {
        SimulationReport report = simulation.run();
        RunResult safe = report.safe();
        RunResult naive = report.naive();

        assertThat(safe.timeouts()).isPositive();                          // the failures really happened
        assertThat(safe.unknownResolved()).isEqualTo(safe.timeouts());     // every unknown outcome was resolved
        assertThat(safe.duplicateCharges()).isZero();
        assertThat(safe.lostCharges()).isZero();
        assertThat(safe.problemsFound()).isZero();

        assertThat(naive.duplicateCharges()).isPositive();                 // the control group shows the damage
        // ...and reconciliation flags every affected payment. (A payment can be both duplicated AND lost:
        // 3 timeouts = 3 captures, then "failed". It's one problem then, so compare with each count separately.)
        assertThat(naive.problemsFound()).isGreaterThanOrEqualTo(naive.duplicateCharges());
        assertThat(naive.problemsFound()).isGreaterThanOrEqualTo(naive.lostCharges());
    }
}