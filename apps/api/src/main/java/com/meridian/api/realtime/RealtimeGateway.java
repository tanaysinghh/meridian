package com.meridian.api.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes dashboard events to an org's subscribers.
 *
 * <p>Successor to {@code emitToOrg}. Socket.IO carried an event name alongside the payload; STOMP
 * has only a destination, so the event name moves into the message body as an {@code event} field
 * and every org shares one destination. The frontend's socket shim reads that field and dispatches
 * to the same {@code .on('pr.scored', ...)} handlers as before, which is what kept the change to
 * the frontend down to a single file.
 *
 * <p>Delivery is best-effort. A dashboard that misses an event refetches on the next one, or on
 * navigation — so a broker failure is logged and swallowed rather than being allowed to fail the
 * webhook request that triggered it.
 */
@Component
public class RealtimeGateway {

    private static final Logger log = LoggerFactory.getLogger(RealtimeGateway.class);

    /** A PR finished scoring — carries the new tier so the UI can flash it. */
    public static final String EVENT_PR_SCORED = "pr.scored";

    /** A PR changed in some way; the dashboard refetches rather than patching in place. */
    public static final String EVENT_PR_UPDATED = "pr.updated";

    private final SimpMessagingTemplate messaging;

    public RealtimeGateway(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** Envelope sent to subscribers: the event name plus whatever that event carries. */
    public record Envelope(@JsonProperty("event") String event, @JsonProperty("data") Map<String, Object> data) {
    }

    public void prScored(UUID orgId, UUID prId, Object score, String tier, String repoFullName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pr_id", prId);
        data.put("score", score);
        data.put("tier", tier);
        data.put("repo", repoFullName);
        emit(orgId, EVENT_PR_SCORED, data);
    }

    public void prUpdated(UUID orgId, UUID prId, String action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pr_id", prId);
        data.put("action", action);
        emit(orgId, EVENT_PR_UPDATED, data);
    }

    private void emit(UUID orgId, String event, Map<String, Object> data) {
        if (orgId == null) {
            return;
        }
        String destination = StompAuthChannelInterceptor.ORG_TOPIC_PREFIX + orgId;
        try {
            messaging.convertAndSend(destination, new Envelope(event, data));
        } catch (Exception ex) {
            log.warn("realtime_emit_failed event={} reason={}", event, ex.getClass().getSimpleName());
        }
    }
}
