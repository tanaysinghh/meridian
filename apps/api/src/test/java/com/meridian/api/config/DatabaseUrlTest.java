package com.meridian.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DATABASE_URL} normalisation.
 *
 * <p>Render and docker-compose both supply the libpq URI form, so this conversion is what lets the
 * environment variable list survive the migration unchanged.
 */
class DatabaseUrlTest {

    @Test
    @DisplayName("splits a libpq URI into a JDBC url and credentials")
    void convertsLibpqUri() {
        Map<String, String> resolved =
                DatabaseUrl.resolve("postgres://meridian:s3cret@localhost:5433/meridian", false);

        assertThat(resolved).containsEntry("spring.datasource.url", "jdbc:postgresql://localhost:5433/meridian");
        assertThat(resolved).containsEntry("spring.datasource.username", "meridian");
        assertThat(resolved).containsEntry("spring.datasource.password", "s3cret");
    }

    @Test
    @DisplayName("accepts the postgresql:// scheme too")
    void acceptsPostgresqlScheme() {
        assertThat(DatabaseUrl.resolve("postgresql://u:p@db.internal:5432/app", false))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.internal:5432/app");
    }

    @Test
    @DisplayName("DATABASE_SSL adds sslmode=require")
    void appliesSslFlag() {
        assertThat(DatabaseUrl.resolve("postgres://u:p@host/db", true))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://host/db?sslmode=require");
    }

    @Test
    @DisplayName("an existing query string is preserved, and not double-flagged")
    void preservesExistingQuery() {
        assertThat(DatabaseUrl.resolve("postgres://u:p@host/db?sslmode=verify-full", true))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://host/db?sslmode=verify-full");

        assertThat(DatabaseUrl.resolve("postgres://u:p@host/db?application_name=meridian", true))
                .containsEntry("spring.datasource.url",
                        "jdbc:postgresql://host/db?application_name=meridian&sslmode=require");
    }

    @Test
    @DisplayName("percent-encoded credentials are decoded")
    void decodesCredentials() {
        Map<String, String> resolved =
                DatabaseUrl.resolve("postgres://user%40corp:p%40ss%3Aword@host/db", false);

        assertThat(resolved).containsEntry("spring.datasource.username", "user@corp");
        assertThat(resolved).containsEntry("spring.datasource.password", "p@ss:word");
    }

    @Test
    @DisplayName("a url already in JDBC form is left alone")
    void passesThroughJdbcForm() {
        assertThat(DatabaseUrl.resolve("jdbc:postgresql://localhost:5432/meridian", false)).isEmpty();
    }

    @Test
    @DisplayName("an absent or unrecognised url yields nothing to override")
    void ignoresUnsupportedValues() {
        assertThat(DatabaseUrl.resolve(null, false)).isEmpty();
        assertThat(DatabaseUrl.resolve("", false)).isEmpty();
        assertThat(DatabaseUrl.resolve("mysql://u:p@host/db", false)).isEmpty();
    }

    @Test
    @DisplayName("a url with no port keeps the driver default")
    void handlesMissingPort() {
        assertThat(DatabaseUrl.resolve("postgres://u:p@db.example.com/meridian", false))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.example.com/meridian");
    }
}
