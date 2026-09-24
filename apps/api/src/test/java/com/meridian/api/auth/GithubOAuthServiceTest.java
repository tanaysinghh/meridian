package com.meridian.api.auth;

import com.meridian.api.config.AppProperties;
import com.meridian.api.orgs.OrgRepository;
import com.meridian.api.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The GitHub authorize URL.
 *
 * <p>Regression cover for a production 500. {@code AuthController} passes this string to
 * {@link URI#create}, which rejects any raw space — and the scope list
 * ({@code "read:user user:email"}) contains one. Built without an explicit encode step, the space
 * survived into the URL and every call to {@code GET /auth/github} threw.
 *
 * <p>It went unnoticed because the endpoint short-circuits to a 503 whenever the app is not
 * configured, which was the case in every environment until the real GitHub App existed. The
 * existing tests covered that 503 path and the callback's state check, but never the configured
 * authorize path.
 */
class GithubOAuthServiceTest {

    private static final String CLIENT_ID = "Iv23liEXAMPLECLIENTID";
    private static final String CALLBACK = "https://meridian-api-il0f.onrender.com/auth/github/callback";

    private static AppProperties props() {
        return new AppProperties(
                "https://meridian-web-1pi4.onrender.com", "", false, true, false,
                new AppProperties.Jwt("t".repeat(40), Duration.ofMinutes(15), 30, "meridian", "meridian-web"),
                4,
                new AppProperties.Ml("https://meridian-ml.onrender.com", "s", 5000),
                new AppProperties.Github(CLIENT_ID, "a-client-secret", CALLBACK, "hook-secret", "5059654", false),
                new AppProperties.Slack(""),
                new AppProperties.Email("", "", ""),
                new AppProperties.RateLimit(900000, 10, 60000, 300));
    }

    private static GithubOAuthService service() {
        return new GithubOAuthService(
                props(),
                RestClient.builder(),
                mock(UserRepository.class),
                mock(OrgRepository.class));
    }

    @Test
    @DisplayName("the authorize URL is parseable by URI.create — the controller depends on it")
    void authorizeUrlIsAValidUri() {
        String url = service().authorizeUrl("a-random-state");

        // This is the exact call AuthController makes. A raw space here is a 500 in production.
        assertThatCode(() -> URI.create(url)).doesNotThrowAnyException();
        assertThat(url).doesNotContain(" ");
    }

    @Test
    @DisplayName("the space between scopes is percent-encoded, not dropped or left raw")
    void scopesAreEncoded() {
        String url = service().authorizeUrl("a-random-state");

        // Either encoding is acceptable to GitHub; what matters is that it is encoded and that
        // both scopes survive.
        assertThat(url).satisfiesAnyOf(
                u -> assertThat(u).contains("scope=read:user%20user:email"),
                u -> assertThat(u).contains("scope=read%3Auser%20user%3Aemail"),
                u -> assertThat(u).contains("scope=read:user+user:email"));

        URI parsed = URI.create(url);
        assertThat(parsed.getQuery()).contains("read:user").contains("user:email");
    }

    @Test
    @DisplayName("all four query parameters are present and carry the configured values")
    void carriesAllParameters() {
        String state = "state-value-123";
        URI parsed = URI.create(service().authorizeUrl(state));

        assertThat(parsed.getScheme()).isEqualTo("https");
        assertThat(parsed.getHost()).isEqualTo("github.com");
        assertThat(parsed.getPath()).isEqualTo("/login/oauth/authorize");

        // getQuery() decodes, so the raw callback and state compare directly.
        String query = parsed.getQuery();
        assertThat(query).contains("client_id=" + CLIENT_ID);
        assertThat(query).contains("redirect_uri=" + CALLBACK);
        assertThat(query).contains("state=" + state);
    }

    @Test
    @DisplayName("configured() requires all three of client id, secret and callback")
    void configuredRequiresAllThree() {
        assertThat(service().configured()).isTrue();

        record Case(String id, String secret, String callback) {}
        for (Case c : new Case[]{
                new Case("", "secret", CALLBACK),
                new Case(CLIENT_ID, "", CALLBACK),
                new Case(CLIENT_ID, "secret", ""),
        }) {
            AppProperties p = new AppProperties(
                    "https://web", "", false, true, false,
                    new AppProperties.Jwt("t".repeat(40), Duration.ofMinutes(15), 30, "meridian", "meridian-web"),
                    4,
                    new AppProperties.Ml("https://ml", "s", 5000),
                    new AppProperties.Github(c.id(), c.secret(), c.callback(), "hook", "1", false),
                    new AppProperties.Slack(""),
                    new AppProperties.Email("", "", ""),
                    new AppProperties.RateLimit(900000, 10, 60000, 300));
            assertThat(p.github().oauthConfigured())
                    .as("id=%s secret=%s callback=%s", c.id(), c.secret(), c.callback())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("state values are unpredictable and unique per attempt")
    void stateIsUnpredictable() {
        GithubOAuthService svc = service();
        String a = svc.newState();
        String b = svc.newState();

        assertThat(a).isNotEqualTo(b).hasSize(48).matches("[0-9a-f]{48}");
    }
}
