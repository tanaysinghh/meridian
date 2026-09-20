package com.meridian.api.incidents.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for {@code /incidents}. */
public final class IncidentDtos {

    private IncidentDtos() {
    }

    /**
     * Body of {@code POST /incidents}.
     *
     * <p>{@code occurred_at} is optional and defaults to now, because incidents are usually filed
     * while they are happening. The two id fields are optional too — you can record that something
     * broke before knowing which change broke it.
     */
    public record Create(
            @JsonProperty("title")
            @NotBlank(message = "Required")
            @Size(min = 1, max = 500, message = "String must contain at most 500 character(s)")
            String title,

            @JsonProperty("severity")
            @NotNull(message = "Required")
            @Pattern(regexp = "sev1|sev2|sev3|sev4", message = "Invalid enum value")
            String severity,

            @JsonProperty("description")
            @Size(max = 10_000, message = "String must contain at most 10000 character(s)")
            String description,

            @JsonProperty("related_pr_id") UUID relatedPrId,

            @JsonProperty("repo_id") UUID repoId,

            @JsonProperty("occurred_at") Instant occurredAt
    ) {
        public String descriptionOrEmpty() {
            return description == null ? "" : description;
        }
    }

    /**
     * A row in {@code GET /incidents}.
     *
     * <p>Carries every {@code incidents} column (the old query selected {@code i.*}) plus the
     * denormalised repo name and linked PR summary the list view renders.
     */
    @JsonPropertyOrder({"id", "org_id", "repo_id", "related_pr_id", "title", "severity",
            "description", "reported_by", "occurred_at", "resolved_at", "created_at",
            "repo_full_name", "pr_number", "pr_title"})
    public record Summary(
            @JsonProperty("id") UUID id,
            @JsonProperty("org_id") UUID orgId,
            @JsonProperty("repo_id") UUID repoId,
            @JsonProperty("related_pr_id") UUID relatedPrId,
            @JsonProperty("title") String title,
            @JsonProperty("severity") String severity,
            @JsonProperty("description") String description,
            @JsonProperty("reported_by") UUID reportedBy,
            @JsonProperty("occurred_at") Instant occurredAt,
            @JsonProperty("resolved_at") Instant resolvedAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("repo_full_name") String repoFullName,
            @JsonProperty("pr_number") Integer prNumber,
            @JsonProperty("pr_title") String prTitle
    ) {}
}
