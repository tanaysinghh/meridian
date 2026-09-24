package com.meridian.api.webhooks;

import com.meridian.api.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook endpoint is unauthenticated and publicly reachable, so this signature check is the
 * only thing standing between a real GitHub delivery and anyone who knows the URL. These tests pin
 * the fail-closed behaviour: every path that cannot positively verify the signature must reject.
 */
class GithubSignatureVerifierTest {

    private static final String SECRET = "a-test-webhook-secret";
    private static final byte[] BODY = """
            {"action":"opened","repository":{"full_name":"acme/platform-api"}}
            """.getBytes(StandardCharsets.UTF_8);

    private static AppProperties props(String webhookSecret, boolean allowUnsigned) {
        return new AppProperties(
                "http://localhost:5173", "", false, false, "Lax", false,
                new AppProperties.Jwt("x".repeat(40), Duration.ofMinutes(15), 30, "meridian", "meridian-web"),
                4,
                new AppProperties.Ml("http://localhost:8000", "", 5000),
                new AppProperties.Github("", "", "", webhookSecret, "", allowUnsigned),
                new AppProperties.Slack(""),
                new AppProperties.Email("", "", ""),
                new AppProperties.RateLimit(900000, 10, 60000, 300));
    }

    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("accepts a correctly signed delivery")
    void acceptsValidSignature() {
        var verifier = new GithubSignatureVerifier(props(SECRET, false));
        assertThat(verifier.verify(BODY, sign(SECRET, BODY))).isTrue();
    }

    @Test
    @DisplayName("rejects a signature computed with a different secret")
    void rejectsWrongSecret() {
        var verifier = new GithubSignatureVerifier(props(SECRET, false));
        assertThat(verifier.verify(BODY, sign("some-other-secret", BODY))).isFalse();
    }

    @Test
    @DisplayName("rejects a valid signature for a different body")
    void rejectsTamperedBody() {
        var verifier = new GithubSignatureVerifier(props(SECRET, false));
        String signatureForOtherBody = sign(SECRET, "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(BODY, signatureForOtherBody)).isFalse();
    }

    @Test
    @DisplayName("rejects a delivery with no signature header")
    void rejectsMissingSignature() {
        var verifier = new GithubSignatureVerifier(props(SECRET, false));
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertThat(verifier.verify(BODY, "")).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed signature header")
    void rejectsMalformedSignature() {
        var verifier = new GithubSignatureVerifier(props(SECRET, false));
        assertThat(verifier.verify(BODY, "not-a-signature")).isFalse();
        assertThat(verifier.verify(BODY, "sha256=zzzz")).isFalse();
        assertThat(verifier.verify(BODY, sign(SECRET, BODY).replace("sha256=", ""))).isFalse();
    }

    @Test
    @DisplayName("fails closed when no secret is configured")
    void failsClosedWithoutSecret() {
        var verifier = new GithubSignatureVerifier(props("", false));
        // Even a well-formed signature cannot be verified without a secret, so nothing is accepted.
        assertThat(verifier.verify(BODY, sign(SECRET, BODY))).isFalse();
        assertThat(verifier.verify(BODY, null)).isFalse();
    }

    @Test
    @DisplayName("the unsigned-webhook escape hatch only applies when no secret is set")
    void unsignedOptInIsDevelopmentOnly() {
        // Development fixture replay: no secret, opt-in enabled -> accepted.
        assertThat(new GithubSignatureVerifier(props("", true)).verify(BODY, null)).isTrue();

        // With a secret configured, the opt-in is irrelevant and verification still applies.
        var withSecret = new GithubSignatureVerifier(props(SECRET, true));
        assertThat(withSecret.verify(BODY, null)).isFalse();
        assertThat(withSecret.verify(BODY, sign("wrong", BODY))).isFalse();
        assertThat(withSecret.verify(BODY, sign(SECRET, BODY))).isTrue();
    }
}
