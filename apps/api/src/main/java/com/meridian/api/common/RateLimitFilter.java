package com.meridian.api.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meridian.api.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP request throttling.
 *
 * <p>Replaces the two {@code express-rate-limit} instances, keeping both their limits and the split
 * between them:
 *
 * <ul>
 *   <li><b>auth</b> — {@code /auth/**}. Tight window, low ceiling
 *       ({@code RATE_LIMIT_AUTH_*}, default 10 per 15 minutes). This is the one that matters: it is
 *       what makes credential stuffing against {@code /auth/login} impractical.</li>
 *   <li><b>api</b> — every other authenticated route ({@code RATE_LIMIT_API_*}, default 300 per
 *       minute), sized to be invisible to normal dashboard use.</li>
 * </ul>
 *
 * <p>Health probes and the webhook endpoint are exempt, as they were before: an orchestrator's
 * liveness checks must not be throttled, and GitHub's delivery volume is bounded by its own retry
 * policy and already gated on a valid signature.
 *
 * <p>Buckets are held in an expiring in-memory cache. That is per-instance rather than shared, so
 * with several replicas the effective ceiling is the limit times the replica count — the same
 * property the previous in-memory {@code express-rate-limit} store had. A shared Redis-backed store
 * is the upgrade path if that ever stops being good enough.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final AppProperties props;
    private final Cache<String, Bucket> authBuckets;
    private final Cache<String, Bucket> apiBuckets;

    public RateLimitFilter(AppProperties props) {
        this.props = props;
        this.authBuckets = newCache(props.rateLimit().authWindowMs());
        this.apiBuckets = newCache(props.rateLimit().apiWindowMs());
    }

    private static Cache<String, Bucket> newCache(long windowMs) {
        // Entries outlive their window by a margin so an in-flight window is never dropped early.
        return Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                .expireAfterAccess(windowMs * 2, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean isAuthRoute = path.startsWith("/auth");
        Cache<String, Bucket> cache = isAuthRoute ? authBuckets : apiBuckets;
        long limit = isAuthRoute ? props.rateLimit().authMax() : props.rateLimit().apiMax();
        long windowMs = isAuthRoute ? props.rateLimit().authWindowMs() : props.rateLimit().apiWindowMs();

        Bucket bucket = cache.get(clientKey(request), key -> newBucket(limit, windowMs));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        // The `standardHeaders: true` equivalent — RFC-style RateLimit-* headers on every response.
        response.setHeader("RateLimit-Limit", Long.toString(limit));
        response.setHeader("RateLimit-Remaining", Long.toString(Math.max(0, probe.getRemainingTokens())));
        response.setHeader("RateLimit-Reset",
                Long.toString(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForReset())));

        if (!probe.isConsumed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"rate_limited\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private static Bucket newBucket(long limit, long windowMs) {
        // Interval refill, not greedy: the whole allowance returns at the end of the window rather
        // than trickling back, which is how express-rate-limit's fixed window behaved.
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, Duration.ofMillis(windowMs))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private static boolean isExempt(String path) {
        return path.startsWith("/health")
                || path.startsWith("/actuator")
                || path.startsWith("/webhooks/")
                || path.startsWith("/live");
    }

    /**
     * The client identity to throttle on.
     *
     * <p>{@code X-Forwarded-For} is honoured only when {@code TRUST_PROXY} says an ingress is in
     * front of us — otherwise any caller could set the header and get a fresh bucket per request.
     * Spring's {@code forward-headers-strategy} has already rewritten
     * {@link HttpServletRequest#getRemoteAddr()} in that case, so reading it is enough.
     */
    private String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
