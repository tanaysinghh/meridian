package com.meridian.api.webhooks;

import com.meridian.api.realtime.RealtimeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /webhooks/github} — the ingestion endpoint.
 *
 * <p>The body is taken as {@code byte[]} rather than a parsed object on purpose: the HMAC is
 * computed over the exact bytes GitHub signed, and parsing then re-serialising would change them.
 * This is also why the route is exempt from CSRF — it has no browser session to double-submit, and
 * the signature is what authenticates it.
 *
 * <p>Handled events match the previous implementation: {@code pull_request} (opened, synchronize,
 * edited, closed, reopened, ready_for_review) and {@code pull_request_review}. Anything else is
 * acknowledged with {@code 200 {"ok": true, "ignored": true}} — GitHub retries on a non-2xx, and
 * there is nothing to retry for an event we simply do not act on.
 */
@RestController
@RequestMapping("/webhooks/github")
public class GithubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookController.class);

    private final GithubSignatureVerifier signatureVerifier;
    private final WebhookIngestService ingest;
    private final RealtimeGateway realtime;
    private final ObjectMapper objectMapper;

    public GithubWebhookController(GithubSignatureVerifier signatureVerifier,
                                   WebhookIngestService ingest,
                                   RealtimeGateway realtime,
                                   ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.ingest = ingest;
        this.realtime = realtime;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) {

        byte[] body = rawBody == null ? new byte[0] : rawBody;

        if (!signatureVerifier.verify(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "bad_signature"));
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_payload"));
        }

        String action = payload.path("action").asString("event");

        if ("pull_request".equals(event)) {
            return handlePullRequest(event, action, payload);
        }
        if ("pull_request_review".equals(event)) {
            return handleReview(payload);
        }

        log.debug("webhook_unhandled_event event={} action={}", event, action);
        Map<String, Object> ignored = new LinkedHashMap<>();
        ignored.put("ok", true);
        ignored.put("ignored", true);
        return ResponseEntity.ok(ignored);
    }

    private ResponseEntity<Map<String, Object>> handlePullRequest(String event, String action, JsonNode payload) {
        String repoFullName = payload.path("repository").path("full_name").asString("");

        Optional<UUID> orgId = ingest.resolveOrgId(repoFullName);
        if (orgId.isEmpty()) {
            // Nothing to attach this to. Accepted so GitHub does not retry.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("ignored", "no_org"));
        }

        WebhookIngestService.IngestResult ingested =
                ingest.ingestPullRequest(orgId.get(), event, action, payload);

        realtime.prUpdated(orgId.get(), ingested.prId(), action);

        if (ingest.shouldScore(action)) {
            // Fire and forget — the response must not wait on the ML service.
            ingest.scoreAndPersist(orgId.get(), ingested, payload.path("diff_text").asString(""));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }

    private ResponseEntity<Map<String, Object>> handleReview(JsonNode payload) {
        String repoFullName = payload.path("repository").path("full_name").asString("");

        Optional<UUID> orgId = ingest.resolveOrgId(repoFullName);
        if (orgId.isPresent()) {
            ingest.ingestReview(orgId.get(), payload)
                    .ifPresent(prId -> realtime.prUpdated(orgId.get(), prId, "review"));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
