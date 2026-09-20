package com.meridian.api.ml;

import com.meridian.api.common.RawJson;
import com.meridian.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the FastAPI risk-scoring service, and scores locally when it cannot be reached.
 *
 * <p>The fallback is the important behaviour here and is unchanged from {@code mlClient.scorePR}:
 * webhook ingestion must not stall because the model is down. A failed call is logged at warn and
 * degrades to {@link #fallbackScore}, which is a transparent heuristic reported with
 * {@code confidence: "low"} and a {@code fallback-heuristic} model version — so a score produced
 * this way is always distinguishable after the fact, in the API response and in the stored row.
 *
 * <p>Requests carry {@code X-Internal-Secret}. The ML service is a public web service on the current
 * hosting plan and that header is the only thing gating {@code /score}, so it is sent on every call
 * whenever a secret is configured.
 */
@Service
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final AppProperties props;
    private final RestClient restClient;

    public MlClient(AppProperties props, RestClient.Builder builder) {
        this.props = props;

        // A slow ML service must not hold a webhook request open. The timeout matches the previous
        // client's 5s body timeout; exceeding it takes the fallback path.
        Duration timeout = Duration.ofMillis(props.ml().timeoutMs());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        this.restClient = builder
                .baseUrl(props.ml().serviceUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Scores a feature vector.
     *
     * @return the model's verdict, or the heuristic fallback — never null, and never throws
     */
    public MlScore score(Map<String, Object> features) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON);

            String secret = props.ml().internalSecret();
            if (secret != null && !secret.isBlank()) {
                request = request.header("X-Internal-Secret", secret);
            }

            JsonNode body = request
                    .body(Map.of("features", features))
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                throw new IllegalStateException("empty_response");
            }

            return new MlScore(
                    BigDecimal.valueOf(body.path("score").asDouble(0.0)).setScale(3, RoundingMode.HALF_UP),
                    body.path("tier").asString("low"),
                    body.path("confidence").asString("low"),
                    body.path("model_version").asString("unknown"),
                    RawJson.ofNullable(body.has("contributions") ? body.get("contributions").toString() : "[]"));

        } catch (Exception ex) {
            // The message can name the host and status but never carries request content, so it is
            // safe to log and is the only clue an operator gets that scoring has degraded.
            log.warn("ml_fallback_scoring reason={} detail={}", ex.getClass().getSimpleName(), ex.getMessage());
            return fallbackScore(features);
        }
    }

    /**
     * The heuristic used when the model is unreachable.
     *
     * <p>Deliberately the same weights the Node implementation used, so a PR scored during an ML
     * outage lands in the same tier it would have before the migration. It is additive and
     * monotonic: size contributes up to 0.55, each sensitive-area flag adds 0.15, poor commit
     * messages add a little, and the total is clamped to 0.05..0.98.
     */
    static MlScore fallbackScore(Map<String, Object> f) {
        double s = 0.15;
        s += Math.min(number(f, "additions") / 1000.0, 0.35);
        s += Math.min(number(f, "changed_files") / 40.0, 0.20);
        if (flag(f, "touches_auth")) {
            s += 0.15;
        }
        if (flag(f, "touches_billing")) {
            s += 0.15;
        }
        if (flag(f, "touches_infra")) {
            s += 0.15;
        }
        if (flag(f, "touches_secret")) {
            s += 0.15;
        }
        double commitQuality = f.containsKey("commit_msg_quality") ? number(f, "commit_msg_quality") : 0.5;
        if (commitQuality < 0.4) {
            s += 0.05;
        }
        s = Math.max(0.05, Math.min(0.98, s));

        BigDecimal score = BigDecimal.valueOf(s).setScale(3, RoundingMode.HALF_UP);
        String tier = tierFor(score.doubleValue());

        // Echo back the first few inputs so the UI's explanation panel is not empty during an
        // outage. The weights are nominal — this is a heuristic, and it says so.
        StringBuilder contributions = new StringBuilder("[");
        int count = 0;
        for (Map.Entry<String, Object> entry : f.entrySet()) {
            if (count == 5) {
                break;
            }
            if (count > 0) {
                contributions.append(',');
            }
            contributions.append("{\"feature\":\"").append(escape(entry.getKey()))
                    .append("\",\"weight\":0.1,\"direction\":\"up\",\"value\":")
                    .append(jsonValue(entry.getValue()))
                    .append('}');
            count++;
        }
        contributions.append(']');

        return new MlScore(score, tier, "low", "fallback-heuristic-0.1", RawJson.of(contributions.toString()));
    }

    /** The tier boundaries, matching the ML service's own {@code _tier()}. */
    static String tierFor(double score) {
        if (score >= 0.85) {
            return "critical";
        }
        if (score >= 0.65) {
            return "high";
        }
        if (score >= 0.35) {
            return "medium";
        }
        return "low";
    }

    private static double number(Map<String, Object> f, String key) {
        Object v = f.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean flag(Map<String, Object> f, String key) {
        Object v = f.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return number(f, key) != 0;
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
