package com.meridian.api.auth;

import com.meridian.api.config.AppProperties;
import com.meridian.api.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies the short-lived access token.
 *
 * <p>Byte-compatible with the tokens the Node service issued: HS256 over the raw {@code JWT_SECRET}
 * bytes, with {@code sub} (user id), {@code org}, {@code role}, and the {@code meridian} /
 * {@code meridian-web} issuer-audience pair. Both are verified on the way back in, so a token
 * minted for a different audience is rejected rather than merely ignored.
 *
 * <p>Refresh tokens are deliberately <em>not</em> JWTs. They stay opaque random strings in the
 * {@code sessions} table so that rotating one is a delete plus an insert, and so a stolen refresh
 * token is revocable — a self-contained JWT would not be.
 */
@Service
public class JwtService {

    private final AppProperties props;
    private final SecretKey key;

    public JwtService(AppProperties props) {
        this.props = props;
        String secret = props.jwt().secret();
        if (secret == null || secret.isBlank()) {
            // StartupChecks turns this into a boot failure under the prod profile; reaching here
            // means a dev run with no secret at all, which the dev profile's default prevents.
            throw new IllegalStateException("[config] JWT_SECRET is required");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            // HS256 needs a 256-bit key. The old jsonwebtoken library silently accepted shorter
            // secrets; padding keeps those dev environments working instead of failing the boot,
            // while prod is held to the real 32-char minimum by StartupChecks.
            byte[] padded = new byte[32];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            for (int i = raw.length; i < 32; i++) {
                padded[i] = (byte) ('0' + (i % 10));
            }
            raw = padded;
        }
        this.key = Keys.hmacShaKeyFor(raw);
    }

    /** Access token for a freshly authenticated or refreshed user. */
    public String signAccess(User user) {
        return signAccess(user.getId(), user.getOrgId(), user.getRole().wire());
    }

    public String signAccess(UUID userId, UUID orgId, String role) {
        Instant now = Instant.now();
        Duration ttl = props.jwt().accessTtl();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("org", orgId == null ? null : orgId.toString())
                .claim("role", role)
                .issuer(props.jwt().issuer())
                .audience().add(props.jwt().audience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, expiry, issuer and audience.
     *
     * @throws JwtException when the token is unusable for any reason — callers translate that into
     *                      a generic 401 without saying which check failed.
     */
    public AccessClaims verifyAccess(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.jwt().issuer())
                .requireAudience(props.jwt().audience())
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        return new AccessClaims(
                UUID.fromString(claims.getSubject()),
                claims.get("org", String.class) == null ? null : UUID.fromString(claims.get("org", String.class)),
                claims.get("role", String.class));
    }

    /** The three claims anything downstream actually reads. */
    public record AccessClaims(UUID userId, UUID orgId, String role) {
    }

    /** Access-token lifetime, used to set the cookie's max-age to match. */
    public Duration accessTtl() {
        return props.jwt().accessTtl();
    }
}
