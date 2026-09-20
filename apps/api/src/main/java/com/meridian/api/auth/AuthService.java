package com.meridian.api.auth;

import com.meridian.api.common.ApiException;
import com.meridian.api.config.AppProperties;
import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Password login, refresh-token rotation and logout.
 *
 * <p>Two properties from the old implementation are load-bearing and preserved exactly:
 *
 * <ol>
 *   <li><b>Login does the same work whether or not the account exists.</b> A miss is compared
 *       against a fixed well-formed bcrypt hash so the response time does not reveal which emails
 *       are registered, and every failure returns the same {@code invalid_credentials}.</li>
 *   <li><b>Refresh tokens rotate.</b> The presented token's row is deleted and a new one inserted
 *       in the same transaction. Presenting an already-rotated token finds nothing and fails.</li>
 * </ol>
 */
@Service
public class AuthService {

    /**
     * A syntactically valid bcrypt hash of a value nobody knows, used as the comparison target when
     * no user matches. Verifying against it costs the same as a real check — which is the point.
     * Carried over verbatim from the Node implementation.
     */
    private static final String DUMMY_HASH = "$2a$12$abcdefghijklmnopqrstuuMh1cnPRfMYtnU8H8mZfOpVjM.eYkxHK";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AuthService(UserRepository users,
                       SessionRepository sessions,
                       PasswordEncoder passwordEncoder,
                       AppProperties props) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    /**
     * Verifies credentials.
     *
     * @throws ApiException 401 {@code invalid_credentials} for a wrong password, an unknown email,
     *                      or an account that only has GitHub sign-in. The three are not
     *                      distinguished on purpose.
     */
    @Transactional(readOnly = true)
    public User authenticate(String email, String rawPassword) {
        Optional<User> found = users.findByEmail(email);
        String hashToCompare = found.map(User::getPasswordHash)
                .filter(h -> h != null && !h.isBlank())
                .orElse(DUMMY_HASH);

        boolean passwordOk = passwordEncoder.matches(rawPassword, hashToCompare);

        if (found.isEmpty() || found.get().getPasswordHash() == null || !passwordOk) {
            throw ApiException.unauthenticated("invalid_credentials");
        }
        return found.get();
    }

    /** Issues a fresh opaque refresh token and records the session. */
    @Transactional
    public String issueRefreshToken(java.util.UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant expires = Instant.now().plus(props.jwt().refreshTtlDays(), ChronoUnit.DAYS);
        sessions.save(new Session(userId, token, expires));
        return token;
    }

    /**
     * Consumes a refresh token and issues its replacement.
     *
     * <p>The delete is what claims the token: {@code deleteByRefreshToken} returns the row count, so
     * if two requests arrive with the same token only the one that actually removed a row proceeds.
     * An expired token is deleted and rejected in the same step, which is how the old handler
     * cleaned up too.
     *
     * @return the user the session belonged to, and the new refresh token
     */
    @Transactional
    public RotatedSession rotate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw ApiException.unauthenticated("no_refresh_token");
        }

        Session session = sessions.findByRefreshToken(presentedToken)
                .orElseThrow(() -> ApiException.unauthenticated("refresh_expired"));

        if (sessions.deleteByRefreshToken(presentedToken) == 0) {
            // Another request consumed it first.
            throw ApiException.unauthenticated("refresh_expired");
        }

        if (session.isExpired(Instant.now())) {
            throw ApiException.unauthenticated("refresh_expired");
        }

        User user = users.findById(session.getUserId())
                .orElseThrow(() -> ApiException.unauthenticated("refresh_expired"));

        return new RotatedSession(user, issueRefreshToken(user.getId()));
    }

    /** Drops the session behind a refresh token, if there is one. Idempotent. */
    @Transactional
    public void revoke(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessions.deleteByRefreshToken(refreshToken);
        }
    }

    public record RotatedSession(User user, String refreshToken) {
    }
}
