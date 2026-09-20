package com.meridian.api.integrations;

import com.meridian.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Sends digest email, or writes it to disk in development.
 *
 * <p>Provider support is the same single entry as before — Resend — with the same three-way
 * behaviour when none is configured: in development the HTML is written to {@code ./outbox/} so the
 * digest can be inspected without an account, and in production email is simply off and says so.
 * Nothing here ever throws on a delivery failure; the caller decides whether a missing digest
 * matters.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";

    private final AppProperties props;
    private final RestClient restClient;
    private final boolean prod;

    public EmailSender(AppProperties props, RestClient.Builder builder, Environment env) {
        this.props = props;
        this.prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(15_000);
        this.restClient = builder.requestFactory(factory).build();
    }

    public Result send(String to, String subject, String html) {
        String provider = props.email().provider().toLowerCase(Locale.ROOT).trim();

        if (provider.isEmpty()) {
            return prod ? disabledInProd(to) : writeToOutbox(to, subject, html);
        }

        if (provider.equals("resend")) {
            return sendViaResend(to, subject, html);
        }

        log.warn("email_provider_not_implemented provider={}", provider);
        return new Result(false, "provider_not_implemented", null);
    }

    private Result disabledInProd(String to) {
        log.warn("email_disabled_no_provider to={}", to);
        return new Result(false, "no_provider_configured", null);
    }

    private Result sendViaResend(String to, String subject, String html) {
        if (props.email().resendKey().isBlank()) {
            throw new IllegalStateException("[email] EMAIL_PROVIDER=resend but RESEND_API_KEY is missing");
        }
        if (props.email().from().isBlank()) {
            throw new IllegalStateException("[email] EMAIL_FROM is required when EMAIL_PROVIDER is set");
        }

        try {
            restClient.post()
                    .uri(RESEND_ENDPOINT)
                    .header("Authorization", "Bearer " + props.email().resendKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", props.email().from(),
                            "to", to,
                            "subject", subject,
                            "html", html))
                    .retrieve()
                    .toBodilessEntity();
            return new Result(true, null, null);
        } catch (Exception ex) {
            // Never log the response body — a provider error can echo back request content.
            log.warn("resend_delivery_failed reason={}", ex.getClass().getSimpleName());
            return new Result(false, "resend_error", null);
        }
    }

    /** Development convenience: the rendered digest lands in ./outbox for inspection. */
    private Result writeToOutbox(String to, String subject, String html) {
        try {
            Path dir = Paths.get("outbox").toAbsolutePath();
            Files.createDirectories(dir);
            String safeName = to.replaceAll("[^\\w]", "_");
            Path file = dir.resolve(System.currentTimeMillis() + "-" + safeName + ".html");
            Files.writeString(file,
                    "<!-- to: " + to + " · subject: " + subject + " -->\n" + html,
                    StandardCharsets.UTF_8);
            log.info("email_dev_written file={} to={}", file, to);
            return new Result(false, null, file.toString());
        } catch (IOException ex) {
            log.warn("email_dev_write_failed reason={}", ex.getClass().getSimpleName());
            return new Result(false, "outbox_write_failed", null);
        }
    }

    public record Result(boolean delivered, String reason, String file) {
    }
}
