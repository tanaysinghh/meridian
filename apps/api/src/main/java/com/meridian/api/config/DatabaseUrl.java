package com.meridian.api.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalises {@code DATABASE_URL} from libpq URI form into the JDBC url and credentials Spring's
 * DataSource expects.
 *
 * <p>This exists so the environment variable list does not change during the migration. Render's
 * {@code fromDatabase: connectionString} and the local docker-compose setup both emit
 * {@code postgres://user:pass@host:port/db}, which is what the Node service consumed directly.
 * Rather than ask every deployment to switch to a {@code jdbc:} prefix, the conversion happens here.
 *
 * <p>Applied from {@code main()} before the context starts, rather than through an
 * {@code EnvironmentPostProcessor}: the DataSource is built very early, and doing it explicitly at
 * the entry point is both guaranteed to run first and easier to follow than an SPI registration.
 *
 * <p>A value already in JDBC form is left alone, so operators who prefer that form can use it.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {
    }

    /**
     * Reads {@code DATABASE_URL} / {@code DATABASE_SSL} from the environment and, when a conversion
     * is needed, publishes {@code spring.datasource.*} as system properties — which outrank
     * {@code application.yml} in Spring's property precedence.
     */
    public static void applyFromEnvironment() {
        String raw = System.getenv("DATABASE_URL");
        boolean wantsSsl = Boolean.parseBoolean(
                System.getenv().getOrDefault("DATABASE_SSL", "false"));

        for (Map.Entry<String, String> entry : resolve(raw, wantsSsl).entrySet()) {
            // Never clobber an explicit -Dspring.datasource.* the operator passed in.
            if (System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * @return the {@code spring.datasource.*} properties implied by the url, or an empty map when
     *         the url is absent or already in JDBC form
     */
    static Map<String, String> resolve(String raw, boolean wantsSsl) {
        Map<String, String> resolved = new LinkedHashMap<>();

        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return resolved;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return resolved;
        }

        URI uri = URI.create(raw);

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());

        // Preserve any query string the operator supplied (e.g. ?sslmode=require), then layer
        // DATABASE_SSL on top when it asks for TLS and the url did not already say so.
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            jdbc.append('?').append(query);
            if (wantsSsl && !query.contains("sslmode=")) {
                // `require` encrypts without verifying the server certificate — the same posture as
                // the old pg pool's `{ rejectUnauthorized: false }`.
                jdbc.append("&sslmode=require");
            }
        } else if (wantsSsl) {
            jdbc.append("?sslmode=require");
        }

        resolved.put("spring.datasource.url", jdbc.toString());

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int split = userInfo.indexOf(':');
            String user = split >= 0 ? userInfo.substring(0, split) : userInfo;
            String pass = split >= 0 ? userInfo.substring(split + 1) : "";
            resolved.put("spring.datasource.username", decode(user));
            resolved.put("spring.datasource.password", decode(pass));
        }

        return resolved;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
