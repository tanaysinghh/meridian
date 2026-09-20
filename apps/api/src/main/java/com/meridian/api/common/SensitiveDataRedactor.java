package com.meridian.api.common;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scrubs secrets out of anything headed for a log line.
 *
 * <p>Replaces the pino {@code redact.paths} list from {@code src/utils/logger.js}. Pino could redact
 * by path because it serialised whole objects; here the logging filter never logs objects at all, so
 * this covers the remaining cases — chiefly webhook payload fragments and upstream error bodies,
 * which are free-form JSON we do not control.
 *
 * <p>The key list is deliberately the same one pino used, plus the webhook signature header.
 */
public final class SensitiveDataRedactor {

    public static final String CENSOR = "[REDACTED]";

    /** Key names whose values must never be logged. Matched case-insensitively. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "password_hash", "refresh_token", "access_token", "token",
            "api_key", "apikey", "jwt", "secret", "client_secret", "authorization",
            "cookie", "x-hub-signature-256", "x-internal-secret", "x-csrf-token",
            "private_key", "resend_api_key", "slack_webhook_url");

    /**
     * Matches {@code "key": "value"} and {@code "key": 123} inside a JSON blob, for any key in
     * {@link #SENSITIVE_KEYS}. Group 1 is everything up to and including the colon and opening
     * quote, so the replacement can keep the key and swap only the value.
     */
    private static final Pattern JSON_SENSITIVE = Pattern.compile(
            "(\"(?:" + String.join("|", SENSITIVE_KEYS.stream().map(Pattern::quote).toList()) + ")\"\\s*:\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private SensitiveDataRedactor() {
    }

    /** True when a header or field name should never have its value logged. */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase());
    }

    /** Redacts sensitive values inside a JSON string, leaving the rest readable. */
    public static String redactJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        return JSON_SENSITIVE.matcher(json).replaceAll("$1\"" + CENSOR + "\"");
    }

    /**
     * Shortens a value to a non-reversible fingerprint for the rare case where a log needs to say
     * "the same token as last time" without saying what the token is.
     */
    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return CENSOR + "(len=" + value.length() + ")";
    }
}
