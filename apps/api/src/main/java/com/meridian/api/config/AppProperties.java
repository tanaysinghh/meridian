package com.meridian.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed view of every Meridian-specific setting.
 *
 * <p>This is the direct successor to the old {@code src/utils/env.js} config object: the same
 * environment variables, the same defaults, the same prod-only requirements. Binding happens once
 * at startup so a malformed value fails the boot rather than the first request that reads it.
 */
@Validated
@ConfigurationProperties(prefix = "meridian")
public record AppProperties(

        @DefaultValue("http://localhost:5173") String webOrigin,

        /* Comma-separated allow-list. Empty means "just the web origin" — never a wildcard. */
        @DefaultValue("") String allowedOrigins,

        @DefaultValue("false") boolean trustProxy,

        /* Secure flag on the auth cookies. False in dev, true in prod. */
        @DefaultValue("false") boolean cookieSecure,

        /* Whether 500 responses carry `message` and `stack`. Dev only. */
        @DefaultValue("false") boolean exposeErrorDetails,

        @NotNull Jwt jwt,

        @DefaultValue("12") @Min(4) int bcryptCost,

        @NotNull Ml ml,

        @NotNull Github github,

        @NotNull Slack slack,

        @NotNull Email email,

        @NotNull RateLimit rateLimit
) {

    public record Jwt(
            @DefaultValue("") String secret,
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("30") int refreshTtlDays,
            @DefaultValue("meridian") String issuer,
            @DefaultValue("meridian-web") String audience
    ) {}

    public record Ml(
            @DefaultValue("http://localhost:8000") String serviceUrl,
            @DefaultValue("") String internalSecret,
            @DefaultValue("5000") long timeoutMs
    ) {}

    public record Github(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String oauthCallback,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("") String appId,
            @DefaultValue("false") boolean allowUnsignedWebhooks
    ) {
        /** Mirrors the old {@code githubConfigured()} helper. */
        public boolean oauthConfigured() {
            return !clientId.isBlank() && !clientSecret.isBlank() && !oauthCallback.isBlank();
        }
    }

    public record Slack(@DefaultValue("") String webhookUrl) {}

    public record Email(
            @DefaultValue("") String provider,
            @DefaultValue("") String from,
            @DefaultValue("") String resendKey
    ) {}

    public record RateLimit(
            @DefaultValue("900000") long authWindowMs,
            @DefaultValue("10") long authMax,
            @DefaultValue("60000") long apiWindowMs,
            @DefaultValue("300") long apiMax
    ) {}

    /**
     * Resolved CORS allow-list: {@code ALLOWED_ORIGINS} when set, otherwise the single
     * {@code WEB_ORIGIN}. Identical to the old {@code csv('ALLOWED_ORIGINS', [WEB_ORIGIN])}.
     */
    public Set<String> resolvedAllowedOrigins() {
        List<String> parsed = new ArrayList<>();
        for (String part : allowedOrigins.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        if (parsed.isEmpty() && !webOrigin.isBlank()) {
            parsed.add(webOrigin);
        }
        return new LinkedHashSet<>(parsed);
    }
}
