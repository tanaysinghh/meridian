package com.meridian.api.webhooks;

import com.meridian.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} over the raw request body.
 *
 * <p>Fail-closed, exactly as before. The webhook endpoint is unauthenticated and world-reachable —
 * this signature is the <em>only</em> thing separating a real GitHub delivery from anyone who knows
 * the URL. A forged delivery could create repos, rewrite PR state and inject arbitrary JSON into
 * {@code pr_events}, so every path that cannot prove the signature returns false:
 *
 * <ul>
 *   <li>no secret configured → reject (unless the dev-only opt-in is on);</li>
 *   <li>no signature header → reject;</li>
 *   <li>length mismatch or any computation failure → reject.</li>
 * </ul>
 *
 * <p>The comparison is constant-time. A byte-by-byte early exit would let an attacker recover a
 * valid signature one byte at a time by timing the responses.
 *
 * <p>{@code ALLOW_UNSIGNED_WEBHOOKS} exists only so the local fixture-replay script can run without
 * a GitHub App. {@code StartupChecks} refuses to boot the prod profile with it enabled.
 */
@Component
public class GithubSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GithubSignatureVerifier.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final AppProperties props;

    public GithubSignatureVerifier(AppProperties props) {
        this.props = props;
    }

    /**
     * @param rawBody   the body exactly as received — the HMAC is over these bytes, so it must not
     *                  have been parsed and re-serialised
     * @param signature the {@code X-Hub-Signature-256} header value, or null
     */
    public boolean verify(byte[] rawBody, String signature) {
        String secret = props.github().webhookSecret();

        if (secret == null || secret.isBlank()) {
            if (props.github().allowUnsignedWebhooks()) {
                log.warn("webhook_signature_bypassed — ALLOW_UNSIGNED_WEBHOOKS is on (development only)");
                return true;
            }
            log.warn("webhook_rejected reason=no_secret_configured");
            return false;
        }

        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String expected = SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));

            byte[] provided = signature.getBytes(StandardCharsets.UTF_8);
            byte[] computed = expected.getBytes(StandardCharsets.UTF_8);

            // MessageDigest.isEqual is constant-time for equal-length inputs and returns early only
            // on a length difference, which is not secret.
            return provided.length == computed.length && MessageDigest.isEqual(provided, computed);
        } catch (Exception ex) {
            log.warn("webhook_signature_check_failed reason={}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
