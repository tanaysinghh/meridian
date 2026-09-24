package com.meridian.api.auth;

import com.meridian.api.config.AppProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cookie attributes for a split-origin deployment.
 *
 * <p>Regression cover for the production bug where no login could ever work. The web and api
 * services live on separate {@code *.onrender.com} subdomains, and {@code onrender.com} is on the
 * Public Suffix List — so the browser treats them as different <em>sites</em>. A
 * {@code SameSite=Lax} cookie is simply never attached to the SPA's cross-site requests, so the
 * session cookie the callback set was never sent back and every request came back 401.
 *
 * <p>{@code SameSite=None} is what makes a cross-site cookie deliverable, and it requires
 * {@code Secure}. These tests pin both, and pin that development keeps the stricter {@code Lax}.
 */
class SplitOriginCookieTest {

    private static AppProperties props(boolean secure, String sameSite) {
        return new AppProperties(
                "https://meridian-web-1pi4.onrender.com", "", false, secure, sameSite, false,
                new AppProperties.Jwt("t".repeat(40), Duration.ofMinutes(15), 30, "meridian", "meridian-web"),
                4,
                new AppProperties.Ml("https://ml", "s", 5000),
                new AppProperties.Github("id", "secret", "https://api/auth/github/callback", "hook", "1", false),
                new AppProperties.Slack(""),
                new AppProperties.Email("", "", ""),
                new AppProperties.RateLimit(900000, 10, 60000, 300));
    }

    private static List<String> setCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("production cookies are SameSite=None and Secure, so they survive a cross-site request")
    void productionCookiesAreCrossSiteCapable() {
        AuthCookies cookies = new AuthCookies(props(true, "None"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setAccess(response, "an-access-token", Duration.ofMinutes(15));
        cookies.setRefresh(response, "a-refresh-token");

        assertThat(setCookies(response)).hasSize(2);
        assertThat(setCookies(response)).allSatisfy(header -> {
            assertThat(header).contains("SameSite=None");
            assertThat(header).contains("Secure");
            assertThat(header).contains("HttpOnly");
            assertThat(header).contains("Path=/");
        });
    }

    @Test
    @DisplayName("development keeps SameSite=Lax and no Secure — the Vite proxy makes it one origin")
    void developmentKeepsLax() {
        AuthCookies cookies = new AuthCookies(props(false, "Lax"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setAccess(response, "an-access-token", Duration.ofMinutes(15));

        String header = setCookies(response).get(0);
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).doesNotContain("Secure");
    }

    @Test
    @DisplayName("SameSite=None is always paired with Secure — browsers reject it otherwise")
    void noneAlwaysImpliesSecure() {
        // A None cookie without Secure is dropped outright by every current browser, which would
        // reintroduce the original bug silently. The prod profile sets both; this pins the pairing.
        AuthCookies cookies = new AuthCookies(props(true, "None"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setOauthState(response, "state-value");

        String header = setCookies(response).get(0);
        assertThat(header).contains("SameSite=None").contains("Secure");
    }

    @Test
    @DisplayName("logout expires all three cookies with the same attributes")
    void logoutClearsEverything() {
        AuthCookies cookies = new AuthCookies(props(true, "None"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.clearAll(response);

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(3);
        assertThat(headers).anySatisfy(h -> assertThat(h).startsWith("mrd_at="));
        assertThat(headers).anySatisfy(h -> assertThat(h).startsWith("mrd_rt="));
        assertThat(headers).anySatisfy(h -> assertThat(h).startsWith("mrd_csrf="));
        // Max-Age=0 is the expiry; the attributes must still match or the browser keeps the original.
        assertThat(headers).allSatisfy(h -> assertThat(h).contains("Max-Age=0").contains("SameSite=None"));
    }

    @Test
    @DisplayName("reading a cookie back off a request works regardless of policy")
    void readsCookiesFromRequest() {
        AuthCookies cookies = new AuthCookies(props(true, "None"));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS, "token-value"));

        assertThat(cookies.read(request, AuthCookies.ACCESS)).isEqualTo("token-value");
        assertThat(cookies.read(request, AuthCookies.REFRESH)).isNull();
    }
}
