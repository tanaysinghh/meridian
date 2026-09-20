package com.meridian.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes sure the {@code mrd_csrf} cookie is actually sent.
 *
 * <p>Spring defers CSRF token generation: the cookie is only written if something reads the token
 * during the request. Nothing here does, because this API has no server-rendered forms — so without
 * this filter a client that has only ever made GET requests would never receive a token, and its
 * first mutation would fail.
 *
 * <p>The old middleware issued the cookie on every safe request for exactly this reason
 * ({@code if (!req.cookies?.[COOKIE]) issueCsrfCookie(res)}). Touching {@code getToken()} here
 * reproduces that: the repository sees the token resolved and persists the cookie.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Resolving the deferred token is what triggers the Set-Cookie.
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
