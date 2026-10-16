package org.payment.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** `./gradlew simulate` starts the app with cutover.simulation.run-on-startup=true, runs both modes, prints, exits. */
@Component
@ConditionalOnProperty(name = "cutover.simulation.run-on-startup", havingValue = "true")
@RequiredArgsConstructor
public class SimulateCommand implements CommandLineRunner {

    private final Simulation simulation;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(String... args) {
        Simulation.SimulationReport report = simulation.run();
        // The safe run must have no duplicates and no lost charges; anything else is a failure.
        boolean ok = report.safe().duplicateCharges() == 0 && report.safe().lostCharges() == 0;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL (the safe run lost or doubled money)");
        System.exit(SpringApplication.exit(context, () -> ok ? 0 : 1));
    }
}