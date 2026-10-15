package org.payment.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * `./gradlew migrate` starts the app with cutover.migration.run-on-startup=true.
 * Once the web server is up, this runs the migration against it, prints the log, and exits:
 * exit code 0 if every check passed, 1 if not.
 */
@Component
@ConditionalOnProperty(name = "cutover.migration.run-on-startup", havingValue = "true")
@RequiredArgsConstructor
public class MigrationCommand implements CommandLineRunner {

    private final MigrationRunner runner;
    private final Environment env;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(String... args) {
        String baseUrl = "http://localhost:" + env.getProperty("local.server.port", "8080");
        MigrationReport report = runner.run(baseUrl);
        int exitCode = report.passed() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}