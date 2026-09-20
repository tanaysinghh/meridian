package com.meridian.api.rules.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.common.RawJson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Request and response shapes for {@code /rules}.
 */
public final class RuleRequests {

    private RuleRequests() {
    }

    /**
     * What to do when a predicate matches.
     *
     * <p>{@code escalate_to} deliberately excludes {@code low}: rules raise a tier and never lower
     * one, so "escalate to low" is not expressible. That asymmetry is the core guarantee of the
     * rules engine — a misconfigured rule can make Meridian noisier, never quieter.
     */
    public record Action(
            @JsonProperty("escalate_to")
            @NotNull(message = "Required")
            @Pattern(regexp = "medium|high|critical", message = "Invalid enum value")
            String escalateTo,

            @JsonProperty("reason")
            @NotBlank(message = "Required")
            @Size(min = 1, max = 500, message = "String must contain at most 500 character(s)")
            String reason
    ) {}

    /** Body of {@code POST /rules}. */
    public record Create(
            @JsonProperty("repo_id") @NotNull(message = "Required") UUID repoId,

            @JsonProperty("name")
            @NotBlank(message = "Required")
            @Size(min = 1, max = 200, message = "String must contain at most 200 character(s)")
            String name,

            @JsonProperty("predicate") @NotNull(message = "Required") @Valid RulePredicate predicate,

            @JsonProperty("action") @NotNull(message = "Required") @Valid Action action,

            @JsonProperty("enabled") Boolean enabled
    ) {
        public boolean enabledOrDefault() {
            // Zod default was true.
            return enabled == null || enabled;
        }
    }

    /**
     * Body of {@code PATCH /rules/{id}}. Every field is optional; an absent field leaves the stored
     * value alone, which is what the old {@code COALESCE($n, column)} update did.
     */
    public record Patch(
            @JsonProperty("name")
            @Size(min = 1, max = 200, message = "String must contain at most 200 character(s)")
            String name,

            @JsonProperty("predicate") @Valid RulePredicate predicate,

            @JsonProperty("action") @Valid Action action,

            @JsonProperty("enabled") Boolean enabled
    ) {}

    /** A row in {@code GET /rules}. */
    @JsonPropertyOrder({"id", "name", "enabled", "predicate", "action", "created_at",
            "repo_full_name", "repo_id"})
    public record Summary(
            @JsonProperty("id") UUID id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("predicate") RawJson predicate,
            @JsonProperty("action") RawJson action,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("repo_full_name") String repoFullName,
            @JsonProperty("repo_id") UUID repoId
    ) {}
}
