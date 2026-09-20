package com.meridian.api.pullrequests.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.common.RawJson;

import java.time.Instant;

/** An entry in the {@code events} array of {@code GET /prs/{id}}, newest first. */
@JsonPropertyOrder({"event_type", "actor_login", "occurred_at", "payload"})
public record PrEventDto(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_login") String actorLogin,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("payload") RawJson payload
) {
}
