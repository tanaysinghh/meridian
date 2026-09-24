package com.meridian.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Publishes the CSRF token so the frontend can actually get hold of it.
 *
 * <p>Two things happen here, for two different reasons.
 *
 * <p><b>Resolving the token writes the cookie.</b> Spring defers CSRF token generation — the cookie
 * is only written if something reads the token during the request. Nothing else does, because this
 * API has no server-rendered forms, so without this filter a client that has only made GET requests
 * would never receive a token and its first mutation would fail. The previous middleware issued the
 * cookie on every safe request for exactly this reason.
 *
 * <p><b>The token is also returned as a response header.</b> In production the SPA and the API are
 * on different {@code *.onrender.com} subdomains, and since {@code onrender.com} is on the Public
 * Suffix List those are separate sites. A cookie set by the API is therefore invisible to
 * {@code document.cookie} on the web origin, so the classic double-submit read is impossible there.
 * Returning the same value in {@link #CSRF_HEADER} — exposed to the browser via
 * {@code Access-Control-Expose-Headers} — gives the frontend a way to read it that works whether
 * the two are same-origin or not. The cookie is still what the server compares against; this only
 * changes how the client discovers the value it must echo back.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    /** Header carrying the token to the client, and the header the client echoes it back in. */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Resolving the deferred token is what triggers the Set-Cookie.
            String value = token.getToken();
            if (value != null && !value.isBlank()) {
                response.setHeader(CSRF_HEADER, value);
            }
        }
        chain.doFilter(request, response);
    }
}
