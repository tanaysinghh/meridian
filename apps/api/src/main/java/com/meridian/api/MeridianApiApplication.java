package com.meridian.api;

import com.meridian.api.config.AppProperties;
import com.meridian.api.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Meridian's REST and realtime backend.
 *
 * <p>PR risk scoring and review intelligence: ingests GitHub webhooks, scores each pull request
 * through the ML service (with a local heuristic fallback), applies per-repo escalation rules, and
 * pushes the result to connected dashboards over STOMP.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class MeridianApiApplication {

    public static void main(String[] args) {
        // Must run before the context starts — the DataSource is built early, and DATABASE_URL
        // arrives in libpq URI form from both Render and docker-compose.
        DatabaseUrl.applyFromEnvironment();

        SpringApplication.run(MeridianApiApplication.class, args);
    }
}
