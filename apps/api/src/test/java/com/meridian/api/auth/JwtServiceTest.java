package com.meridian.api.auth;

import com.meridian.api.config.AppProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access-token signing and verification.
 *
 * <p>These check that verification actually rejects — a token signed with another key, minted for
 * another audience, or already expired must not authenticate. A permissive verifier here would make
 * every authenticated endpoint reachable.
 */
class JwtServiceTest {

    private static final String SECRET = "a-secret-long-enough-for-hs256-signing-please";

    private static AppProperties props(String secret, Duration ttl, String issuer, String audience) {
        return new AppProperties(
                "http://localhost:5173", "", false, false, "Lax", false,
                new AppProperties.Jwt(secret, ttl, 30, issuer, audience),
                4,
                new AppProperties.Ml("http://localhost:8000", "", 5000),
                new AppProperties.Github("", "", "", "s", "", false),
                new AppProperties.Slack(""),
                new AppProperties.Email("", "", ""),
                new AppProperties.RateLimit(900000, 10, 60000, 300));
    }

    private static JwtService service() {
        return new JwtService(props(SECRET, Duration.ofMinutes(15), "meridian", "meridian-web"));
    }

    @Test
    @DisplayName("a freshly signed token verifies and carries its claims")
    void signAndVerifyRoundTrip() {
        JwtService jwt = service();
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        var claims = jwt.verifyAccess(jwt.signAccess(userId, orgId, "admin"));

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.orgId()).isEqualTo(orgId);
        assertThat(claims.role()).isEqualTo("admin");
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsForeignSignature() {
        String token = new JwtService(props("a-completely-different-secret-of-sufficient-length",
                Duration.ofMinutes(15), "meridian", "meridian-web"))
                .signAccess(UUID.randomUUID(), UUID.randomUUID(), "admin");

        assertThatThrownBy(() -> service().verifyAccess(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void rejectsTamperedToken() {
        JwtService jwt = service();
        String token = jwt.signAccess(UUID.randomUUID(), UUID.randomUUID(), "developer");

        // Flip a character in the payload segment; the signature no longer matches.
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];

        assertThatThrownBy(() -> jwt.verifyAccess(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token minted for a different audience is rejected")
    void rejectsWrongAudience() {
        String token = new JwtService(props(SECRET, Duration.ofMinutes(15), "meridian", "some-other-app"))
                .signAccess(UUID.randomUUID(), UUID.randomUUID(), "admin");

        assertThatThrownBy(() -> service().verifyAccess(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void rejectsWrongIssuer() {
        String token = new JwtService(props(SECRET, Duration.ofMinutes(15), "not-meridian", "meridian-web"))
                .signAccess(UUID.randomUUID(), UUID.randomUUID(), "admin");

        assertThatThrownBy(() -> service().verifyAccess(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        // Negative TTL puts the expiry in the past at signing time.
        JwtService expiring = new JwtService(
                props(SECRET, Duration.ofMinutes(-10), "meridian", "meridian-web"));
        String token = expiring.signAccess(UUID.randomUUID(), UUID.randomUUID(), "admin");

        assertThatThrownBy(() -> service().verifyAccess(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("garbage input is rejected rather than throwing something unexpected")
    void rejectsGarbage() {
        JwtService jwt = service();
        assertThatThrownBy(() -> jwt.verifyAccess("not-a-jwt")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jwt.verifyAccess("")).isInstanceOf(Exception.class);
    }
}
