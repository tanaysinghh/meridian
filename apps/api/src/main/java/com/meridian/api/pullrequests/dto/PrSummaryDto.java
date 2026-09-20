package com.meridian.api.pullrequests.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.common.RawJson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row in {@code GET /prs}.
 *
 * <p>Column-for-column the projection the old list query selected, including the three fields that
 * come from the latest risk score via a LATERAL join and are therefore null for a PR that has not
 * been scored yet.
 */
@JsonPropertyOrder({
        "id", "number", "title", "author_login", "author_avatar", "state",
        "additions", "deletions", "changed_files", "commits_count",
        "labels", "requested_reviewers", "url", "opened_at", "updated_at", "merged_at",
        "repo_full_name", "score", "tier", "confidence", "rule_hits"
})
public record PrSummaryDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("number") int number,
        @JsonProperty("title") String title,
        @JsonProperty("author_login") String authorLogin,
        @JsonProperty("author_avatar") String authorAvatar,
        @JsonProperty("state") String state,
        @JsonProperty("additions") int additions,
        @JsonProperty("deletions") int deletions,
        @JsonProperty("changed_files") int changedFiles,
        @JsonProperty("commits_count") int commitsCount,
        @JsonProperty("labels") List<String> labels,
        @JsonProperty("requested_reviewers") List<String> requestedReviewers,
        @JsonProperty("url") String url,
        @JsonProperty("opened_at") Instant openedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("merged_at") Instant mergedAt,
        @JsonProperty("repo_full_name") String repoFullName,
        @JsonProperty("score") BigDecimal score,
        @JsonProperty("tier") String tier,
        @JsonProperty("confidence") String confidence,
        @JsonProperty("rule_hits") RawJson ruleHits
) {
}
