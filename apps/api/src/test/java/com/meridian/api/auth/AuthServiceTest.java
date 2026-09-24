package com.meridian.api.auth;

import com.meridian.api.common.ApiException;
import com.meridian.api.config.AppProperties;
import com.meridian.api.users.Role;
import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Login and refresh-token rotation.
 *
 * <p>The two behaviours worth protecting are both security properties rather than features: login
 * must not reveal which email addresses are registered, and a refresh token must be usable exactly
 * once.
 */
class AuthServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private UserRepository users;
    private SessionRepository sessions;
    private AuthService authService;
    // Cost 4: these tests exercise the comparison logic, not bcrypt's work factor.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private static AppProperties props() {
        return new AppProperties(
                "http://localhost:5173", "", false, false, "Lax", true,
                new AppProperties.Jwt("t".repeat(40), Duration.ofMinutes(15), 30, "meridian", "meridian-web"),
                4,
                new AppProperties.Ml("http://localhost:8000", "", 5000),
                new AppProperties.Github("", "", "", "s", "", false),
                new AppProperties.Slack(""),
                new AppProperties.Email("", "", ""),
                new AppProperties.RateLimit(900000, 10, 60000, 300));
    }

    private User userWithPassword(String rawPassword) {
        User user = new User(ORG_ID, "dev@example.test", Role.DEVELOPER);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setPasswordHash(encoder.encode(rawPassword));
        return user;
    }

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(SessionRepository.class);
        authService = new AuthService(users, sessions, encoder, props());
    }

    @Test
    @DisplayName("correct credentials authenticate")
    void authenticatesValidCredentials() {
        User user = userWithPassword("correct-horse-battery");
        when(users.findByEmail("dev@example.test")).thenReturn(Optional.of(user));

        assertThat(authService.authenticate("dev@example.test", "correct-horse-battery"))
                .isSameAs(user);
    }

    @Test
    @DisplayName("a wrong password and an unknown account fail identically")
    void doesNotRevealWhetherAccountExists() {
        when(users.findByEmail("dev@example.test"))
                .thenReturn(Optional.of(userWithPassword("correct-horse-battery")));
        when(users.findByEmail("ghost@example.test")).thenReturn(Optional.empty());

        // Same status and same code, so a caller cannot enumerate registered addresses.
        assertThatThrownBy(() -> authService.authenticate("dev@example.test", "wrong"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid_credentials")
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThatThrownBy(() -> authService.authenticate("ghost@example.test", "wrong"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid_credentials")
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a GitHub-only account cannot be logged into with a password")
    void githubOnlyAccountRejectsPasswordLogin() {
        User user = new User(ORG_ID, "gh@example.test", Role.DEVELOPER);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setPasswordHash(null);
        when(users.findByEmail("gh@example.test")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.authenticate("gh@example.test", "anything"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid_credentials");
    }

    @Test
    @DisplayName("refresh consumes the presented token and issues a new one")
    void rotatesRefreshToken() {
        Session session = new Session(USER_ID, "old-token", Instant.now().plus(30, ChronoUnit.DAYS));
        when(sessions.findByRefreshToken("old-token")).thenReturn(Optional.of(session));
        when(sessions.deleteByRefreshToken("old-token")).thenReturn(1);
        when(users.findById(USER_ID)).thenReturn(Optional.of(userWithPassword("x")));
        when(sessions.save(any(Session.class))).thenAnswer(i -> i.getArgument(0));

        AuthService.RotatedSession rotated = authService.rotate("old-token");

        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo("old-token");
        verify(sessions).deleteByRefreshToken("old-token");
        verify(sessions).save(any(Session.class));
    }

    @Test
    @DisplayName("a token already consumed by a concurrent request is rejected")
    void rejectsAlreadyConsumedToken() {
        Session session = new Session(USER_ID, "old-token", Instant.now().plus(30, ChronoUnit.DAYS));
        when(sessions.findByRefreshToken("old-token")).thenReturn(Optional.of(session));
        // The delete removed nothing: another request won the race and already rotated it.
        when(sessions.deleteByRefreshToken("old-token")).thenReturn(0);

        assertThatThrownBy(() -> authService.rotate("old-token"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh_expired");

        verify(sessions, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("an expired token is rejected and cleaned up")
    void rejectsExpiredToken() {
        Session expired = new Session(USER_ID, "old-token", Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessions.findByRefreshToken("old-token")).thenReturn(Optional.of(expired));
        when(sessions.deleteByRefreshToken("old-token")).thenReturn(1);

        assertThatThrownBy(() -> authService.rotate("old-token"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh_expired");

        verify(sessions).deleteByRefreshToken("old-token");
        verify(sessions, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("an unknown or absent refresh token is rejected")
    void rejectsUnknownToken() {
        when(sessions.findByRefreshToken(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.rotate("never-issued"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh_expired");

        assertThatThrownBy(() -> authService.rotate(null))
                .isInstanceOf(ApiException.class)
                .hasMessage("no_refresh_token");
    }

    @Test
    @DisplayName("issued refresh tokens are unpredictable and unique")
    void issuesUnpredictableTokens() {
        when(sessions.save(any(Session.class))).thenAnswer(i -> i.getArgument(0));

        String a = authService.issueRefreshToken(USER_ID);
        String b = authService.issueRefreshToken(USER_ID);

        assertThat(a).isNotEqualTo(b);
        // 32 random bytes, hex encoded.
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
    }
}
