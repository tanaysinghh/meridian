package com.meridian.api.auth;

import com.meridian.api.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reads and writes the four cookies the browser session runs on.
 *
 * <p>Names, flags and lifetimes all carry over from {@code src/routes/auth.js} and
 * {@code src/middleware/csrf.js} unchanged, because the frontend reads {@code mrd_csrf} by name and
 * the rest have to keep matching the tokens already in users' browsers:
 *
 * <ul>
 *   <li>{@code mrd_at} — access token. HttpOnly, lifetime matches the JWT's.</li>
 *   <li>{@code mrd_rt} — refresh token. HttpOnly, {@code JWT_REFRESH_TTL_DAYS}.</li>
 *   <li>{@code mrd_csrf} — double-submit token. Deliberately <em>not</em> HttpOnly; the frontend has
 *       to read it to echo it back in {@code X-CSRF-Token}.</li>
 *   <li>{@code mrd_oauth_state} — short-lived GitHub OAuth state. HttpOnly, 10 minutes.</li>
 * </ul>
 *
 * <p>All are {@code SameSite=Lax} and {@code Secure} only in production, matching
 * {@code secure: config.isProd}. Lax is what makes the double-submit defence work: a cross-origin
 * form post cannot read the CSRF cookie, so it cannot produce a matching header.
 */
@Component
public class AuthCookies {

    public static final String ACCESS = "mrd_at";
    public static final String REFRESH = "mrd_rt";
    public static final String CSRF = "mrd_csrf";
    public static final String OAUTH_STATE = "mrd_oauth_state";

    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

    private final AppProperties props;

    public AuthCookies(AppProperties props) {
        this.props = props;
    }

    public void setAccess(HttpServletResponse response, String token, Duration ttl) {
        write(response, build(ACCESS, token, ttl, true));
    }

    public void setRefresh(HttpServletResponse response, String token) {
        write(response, build(REFRESH, token, Duration.ofDays(props.jwt().refreshTtlDays()), true));
    }

    public void setOauthState(HttpServletResponse response, String state) {
        write(response, build(OAUTH_STATE, state, OAUTH_STATE_TTL, true));
    }

    public void clearOauthState(HttpServletResponse response) {
        write(response, build(OAUTH_STATE, "", Duration.ZERO, true));
    }

    /**
     * Clears the session cookies on logout. The CSRF cookie goes too, so the next safe request
     * mints a fresh one rather than letting a pre-login token linger.
     */
    public void clearAll(HttpServletResponse response) {
        write(response, build(ACCESS, "", Duration.ZERO, true));
        write(response, build(REFRESH, "", Duration.ZERO, true));
        write(response, build(CSRF, "", Duration.ZERO, false));
    }

    public String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String name, String value, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private void write(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
