package com.meridian.api.common;

import com.meridian.api.auth.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One structured line per request.
 *
 * <p>Successor to {@code requestLogger()} in {@code src/utils/logger.js}, and it keeps that
 * function's most important property: it logs <em>only</em> method, path, status, duration and user
 * id. Headers and bodies are never touched, so the {@code Authorization} header, the session
 * cookies and any password in a login body cannot reach the log in the first place. See
 * {@link SensitiveDataRedactor} for the cases where a value does have to be logged.
 *
 * <p>Level follows status, as before: 5xx logs at error, 4xx at warn, everything else at info.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.meridian.api.request");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            int status = response.getStatus();
            String userId = currentUserId();

            if (status >= 500) {
                log.error("request method={} path={} status={} ms={} userId={}",
                        request.getMethod(), request.getRequestURI(), status, ms, userId);
            } else if (status >= 400) {
                log.warn("request method={} path={} status={} ms={} userId={}",
                        request.getMethod(), request.getRequestURI(), status, ms, userId);
            } else {
                log.info("request method={} path={} status={} ms={} userId={}",
                        request.getMethod(), request.getRequestURI(), status, ms, userId);
            }
        }
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id().toString();
        }
        return "null";
    }
}
