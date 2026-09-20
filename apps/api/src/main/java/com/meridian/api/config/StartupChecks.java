package com.meridian.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-fast configuration guards.
 *
 * <p>Successor to the top-level throws in {@code src/utils/env.js}. Each rule below is carried over
 * unchanged: in production a missing secret aborts the boot rather than letting the service come up
 * in a weaker posture, and in development the same gaps are only warnings.
 *
 * <p>Implemented as an {@link InitializingBean} so the failure happens during context refresh —
 * before the HTTP connector starts accepting traffic.
 */
@Component
public class StartupChecks implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    /** The dev stand-in from application-dev.yml. Never valid outside the dev profile. */
    private static final String DEV_JWT_SECRET = "dev-only-insecure-jwt-secret-do-not-use-in-prod";

    private final AppProperties props;
    private final Environment env;

    public StartupChecks(AppProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");

        String secret = props.jwt().secret();

        if (prod) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("[config] JWT_SECRET is required in production");
            }
            if (secret.length() < 32) {
                throw new IllegalStateException("[config] JWT_SECRET must be at least 32 chars in production");
            }
            if (DEV_JWT_SECRET.equals(secret)) {
                throw new IllegalStateException("[config] the development JWT_SECRET fallback must not be used in production");
            }
            if (isBlank(env.getProperty("DATABASE_URL")) && isBlank(env.getProperty("spring.datasource.url"))) {
                throw new IllegalStateException("[config] DATABASE_URL is required in production");
            }
            if (props.github().webhookSecret().isBlank()) {
                throw new IllegalStateException("[config] GITHUB_WEBHOOK_SECRET is required in production");
            }
            if (props.github().allowUnsignedWebhooks()) {
                throw new IllegalStateException("[config] ALLOW_UNSIGNED_WEBHOOKS must never be enabled in production");
            }
            if (props.resolvedAllowedOrigins().stream().anyMatch(o -> o.contains("*"))) {
                throw new IllegalStateException("[config] wildcard CORS origins are not permitted");
            }
        } else {
            if (DEV_JWT_SECRET.equals(secret)) {
                log.warn("JWT_SECRET is unset — using the insecure development fallback. "
                        + "Set JWT_SECRET before running anything that matters.");
            }
            if (props.github().allowUnsignedWebhooks()) {
                log.warn("ALLOW_UNSIGNED_WEBHOOKS is on — GitHub webhook signatures are NOT being "
                        + "verified. Development only; this is refused under the prod profile.");
            }
            if (props.github().webhookSecret().isBlank() && !props.github().allowUnsignedWebhooks()) {
                log.warn("GITHUB_WEBHOOK_SECRET is unset — webhook deliveries will be rejected.");
            }
        }

        log.info("config_ok profile={} origins={} ml={} github_oauth={} slack={} email={}",
                prod ? "prod" : String.join(",", env.getActiveProfiles()),
                props.resolvedAllowedOrigins(),
                props.ml().serviceUrl(),
                props.github().oauthConfigured(),
                !props.slack().webhookUrl().isBlank(),
                !props.email().provider().isBlank());
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
