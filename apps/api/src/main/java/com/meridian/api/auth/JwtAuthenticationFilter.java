package com.meridian.api.auth;

import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns the access token into an authenticated {@code SecurityContext}.
 *
 * <p>Direct successor to {@code requireAuth} in {@code src/middleware/auth.js}, including its token
 * precedence: the {@code mrd_at} cookie first, then an {@code Authorization: Bearer} header. The
 * cookie is what the browser uses; the header exists for scripted callers.
 *
 * <p>Like the middleware it replaces, a valid signature is not enough on its own — the user row is
 * re-read on every request. That costs a query but means a revoked account or a changed role stops
 * working immediately instead of lingering until the 15-minute token expires.
 *
 * <p>The filter never rejects anything itself. A missing or bad token simply leaves the context
 * anonymous and lets the authorization rules decide, which keeps the public routes public and gives
 * one consistent 401 shape for everything else.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository users;
    private final AuthCookies cookies;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users, AuthCookies cookies) {
        this.jwtService = jwtService;
        this.users = users;
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            extractToken(request).ifPresent(token -> authenticate(token, request));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            JwtService.AccessClaims claims = jwtService.verifyAccess(token);
            Optional<User> found = users.findById(claims.userId());
            if (found.isEmpty()) {
                // Token is valid but the account is gone. Stay anonymous.
                return;
            }
            AuthenticatedUser principal = AuthenticatedUser.from(found.get());
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority(principal.role().authority())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception ex) {
            // Expired, tampered, wrong audience — all indistinguishable to the caller.
            log.debug("access_token_rejected path={} reason={}", request.getRequestURI(), ex.getClass().getSimpleName());
        }
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String cookie = cookies.read(request, AuthCookies.ACCESS);
        if (cookie != null && !cookie.isBlank()) {
            return Optional.of(cookie);
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String value = header.substring(BEARER.length()).trim();
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
