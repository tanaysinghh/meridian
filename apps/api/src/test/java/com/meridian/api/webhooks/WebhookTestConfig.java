package com.meridian.api.webhooks;

import com.meridian.api.config.AppProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Supporting beans for the webhook controller slice test.
 *
 * <p>Only the verifier is declared here. {@link AppProperties} comes from the real configuration
 * binding — the test sets {@code meridian.github.webhook-secret} through the slice's {@code
 * properties} attribute — so the verifier under test is wired exactly as it is in production.
 */
@TestConfiguration
public class WebhookTestConfig {

    public static final String SECRET = "webhook-secret-under-test";

    @Bean
    GithubSignatureVerifier githubSignatureVerifier(AppProperties props) {
        return new GithubSignatureVerifier(props);
    }

    /** Produces the header value GitHub would send for this body and secret. */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of()
                    .formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
