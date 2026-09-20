package com.meridian.api.auth;

import com.meridian.api.auth.dto.LoginRequest;
import com.meridian.api.common.ApiException;
import com.meridian.api.config.AppProperties;
import com.meridian.api.users.User;
import com.meridian.api.users.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * {@code /auth} — password login, session refresh, logout, and the GitHub OAuth handshake.
 *
 * <p>Routes, request shapes and response bodies are unchanged from the Express router. The cookie
 * side effects are the meaningful part of each response: {@link AuthCookies} sets {@code mrd_at} and
 * {@code mrd_rt} on success and clears them on logout, and Spring Security's CSRF repository
 * refreshes {@code mrd_csrf} alongside.
 */
@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final GithubOAuthService githubOAuth;
    private final JwtService jwtService;
    private final AuthCookies cookies;
    private final AppProperties props;

    public AuthController(AuthService authService,
                          GithubOAuthService githubOAuth,
                          JwtService jwtService,
                          AuthCookies cookies,
                          AppProperties props) {
        this.authService = authService;
        this.githubOAuth = githubOAuth;
        this.jwtService = jwtService;
        this.cookies = cookies;
        this.props = props;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        // The email is already trimmed and lowercased — LoginRequest normalises on construction.
        User user = authService.authenticate(body.email(), body.password());
        String refresh = authService.issueRefreshToken(user.getId());
        setSessionCookies(response, user, refresh);
        return Map.of("user", UserDto.from(user));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.revoke(cookies.read(request, AuthCookies.REFRESH));
        cookies.clearAll(response);
        return Map.of("ok", true);
    }

    /**
     * Rotates the session. The response body stays {@code {"ok": true}} — the new tokens travel in
     * Set-Cookie headers, never in the body.
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        AuthService.RotatedSession rotated = authService.rotate(cookies.read(request, AuthCookies.REFRESH));
        setSessionCookies(response, rotated.user(), rotated.refreshToken());
        return Map.of("ok", true);
    }

    // --- GitHub OAuth --------------------------------------------------------

    @GetMapping("/github")
    public ResponseEntity<?> githubAuthorize(HttpServletResponse response) {
        if (!githubOAuth.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "github_oauth_not_configured",
                    "message", "GitHub OAuth is not configured on this server. "
                            + "Set GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, and GITHUB_OAUTH_CALLBACK."));
        }
        String state = githubOAuth.newState();
        cookies.setOauthState(response, state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(githubOAuth.authorizeUrl(state)))
                .build();
    }

    @GetMapping("/github/callback")
    public ResponseEntity<?> githubCallback(
            @RequestParam("code") @NotBlank @Size(max = 500) String code,
            @RequestParam("state") @NotBlank @Size(max = 200) String state,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (!githubOAuth.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "github_oauth_not_configured"));
        }

        // Compare against the cookie this server set when it started the flow. A callback that
        // arrives without one, or with a different one, did not originate here.
        String expected = cookies.read(request, AuthCookies.OAUTH_STATE);
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, state)) {
            throw ApiException.badRequest("invalid_oauth_state");
        }
        cookies.clearOauthState(response);

        User user = githubOAuth.completeLogin(code);
        String refresh = authService.issueRefreshToken(user.getId());
        setSessionCookies(response, user, refresh);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(props.webOrigin() + "/app"))
                .build();
    }

    private void setSessionCookies(HttpServletResponse response, User user, String refreshToken) {
        cookies.setAccess(response, jwtService.signAccess(user), jwtService.accessTtl());
        cookies.setRefresh(response, refreshToken);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
