package com.meridian.api.auth;

import com.meridian.api.common.ApiException;
import com.meridian.api.config.AppProperties;
import com.meridian.api.config.JacksonConfig;
import com.meridian.api.users.Role;
import com.meridian.api.users.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login flow at the HTTP boundary.
 *
 * <p>Two things matter here beyond the happy path: the response body must never carry a token (they
 * belong in HttpOnly cookies, out of reach of page scripts), and it must never carry the password
 * hash — the previous implementation had to delete that field by hand before responding.
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "meridian.jwt.secret=a-test-secret-long-enough-for-hs256-signing",
                "meridian.expose-error-details=true",
                "meridian.cookie-secure=false",
                "meridian.web-origin=http://localhost:5173"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, AuthControllerTest.Config.class})
class AuthControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    /**
     * {@link AppProperties} itself comes from the real binding (driven by the {@code properties}
     * above), so only the collaborators the controller needs are declared here.
     */
    @TestConfiguration
    static class Config {
        @Bean
        AuthCookies authCookies(AppProperties props) {
            return new AuthCookies(props);
        }

        @Bean
        JwtService jwtService(AppProperties props) {
            return new JwtService(props);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private GithubOAuthService githubOAuth;

    private static User user() {
        User u = new User(ORG_ID, "dev@example.test", Role.TEAM_LEAD);
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setName("Dev Example");
        u.setGithubLogin("dev-example");
        u.setPasswordHash("$2a$12$not-a-real-hash-but-set");
        return u;
    }

    @Test
    @DisplayName("a successful login returns the user and sets the session cookies")
    void loginSucceeds() throws Exception {
        when(authService.authenticate("dev@example.test", "correct-horse")).thenReturn(user());
        when(authService.issueRefreshToken(USER_ID)).thenReturn("a-refresh-token");

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"dev@example.test\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.user.org_id").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.user.email").value("dev@example.test"))
                .andExpect(jsonPath("$.user.role").value("team_lead"))
                .andExpect(jsonPath("$.user.github_login").value("dev-example"))
                // Nothing secret in the body.
                .andExpect(jsonPath("$.user.password_hash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                // Tokens travel in HttpOnly cookies instead.
                .andExpect(cookie().exists("mrd_at"))
                .andExpect(cookie().httpOnly("mrd_at", true))
                .andExpect(cookie().exists("mrd_rt"))
                .andExpect(cookie().httpOnly("mrd_rt", true))
                .andExpect(cookie().value("mrd_rt", "a-refresh-token"))
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("SameSite=Lax"))));
    }

    @Test
    @DisplayName("the email is normalised before lookup")
    void loginNormalisesEmail() throws Exception {
        when(authService.authenticate(anyString(), anyString())).thenReturn(user());
        when(authService.issueRefreshToken(any())).thenReturn("t");

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"  DEV@Example.TEST \",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk());

        verify(authService).authenticate(eq("dev@example.test"), eq("correct-horse"));
    }

    @Test
    @DisplayName("bad credentials are a 401 with a generic code")
    void loginRejectsBadCredentials() throws Exception {
        when(authService.authenticate(anyString(), anyString()))
                .thenThrow(ApiException.unauthenticated("invalid_credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"dev@example.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(cookie().doesNotExist("mrd_at"));
    }

    @Test
    @DisplayName("a malformed body is a 400 before any lookup happens")
    void loginValidatesBody() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("invalid_request")));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"dev@example.test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("logout revokes the session and clears the cookies")
    void logoutClearsCookies() throws Exception {
        mockMvc.perform(post("/auth/logout").cookie(new jakarta.servlet.http.Cookie("mrd_rt", "a-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(cookie().maxAge("mrd_at", 0))
                .andExpect(cookie().maxAge("mrd_rt", 0))
                .andExpect(cookie().maxAge("mrd_csrf", 0));

        verify(authService).revoke("a-refresh-token");
    }

    @Test
    @DisplayName("refresh rotates the cookies and returns no token in the body")
    void refreshRotates() throws Exception {
        when(authService.rotate("old-token"))
                .thenReturn(new AuthService.RotatedSession(user(), "new-refresh-token"));

        mockMvc.perform(post("/auth/refresh").cookie(new jakarta.servlet.http.Cookie("mrd_rt", "old-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().value("mrd_rt", "new-refresh-token"))
                .andExpect(cookie().exists("mrd_at"));
    }

    @Test
    @DisplayName("GitHub sign-in returns a clear 503 when it is not configured")
    void githubOauthUnconfigured() throws Exception {
        when(githubOAuth.configured()).thenReturn(false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/github"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("github_oauth_not_configured"));
    }

    @Test
    @DisplayName("the OAuth callback refuses a request whose state does not match the cookie")
    void githubCallbackRequiresMatchingState() throws Exception {
        when(githubOAuth.configured()).thenReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/auth/github/callback")
                        .param("code", "abc")
                        .param("state", "attacker-supplied")
                        .cookie(new jakarta.servlet.http.Cookie("mrd_oauth_state", "the-real-state")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_oauth_state"));

        // And with no state cookie at all.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/auth/github/callback")
                        .param("code", "abc")
                        .param("state", "anything"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_oauth_state"));
    }
}
