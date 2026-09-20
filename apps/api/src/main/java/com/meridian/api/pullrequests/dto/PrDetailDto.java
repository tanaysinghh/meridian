package com.meridian.api.pullrequests.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.common.RawJson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The {@code pr} object in {@code GET /prs/{id}}.
 *
 * <p>The old handler selected {@code p.*} plus the latest score and the outcome row, so this carries
 * every {@code pull_requests} column — including {@code repo_id}, {@code github_id} and {@code body},
 * which the list view omits — followed by the joined fields. Anything from the score or outcome join
 * is null when the PR has not been scored or has no recorded outcome.
 */
@JsonPropertyOrder({
        "id", "repo_id", "number", "github_id", "title", "body",
        "author_login", "author_avatar", "state", "draft", "base_ref", "head_ref",
        "additions", "deletions", "changed_files", "commits_count",
        "file_paths", "labels", "requested_reviewers", "url",
        "opened_at", "updated_at", "merged_at", "closed_at", "first_review_at", "approved_at",
        "repo_full_name", "score", "tier", "confidence", "features", "contributions",
        "rule_hits", "model_version", "scored_at",
        "reverted", "hotfixed", "caused_incident", "outcome_notes"
})
public record PrDetailDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("repo_id") UUID repoId,
        @JsonProperty("number") int number,
        @JsonProperty("github_id") Long githubId,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("author_login") String authorLogin,
        @JsonProperty("author_avatar") String authorAvatar,
        @JsonProperty("state") String state,
        @JsonProperty("draft") boolean draft,
        @JsonProperty("base_ref") String baseRef,
        @JsonProperty("head_ref") String headRef,
        @JsonProperty("additions") int additions,
        @JsonProperty("deletions") int deletions,
        @JsonProperty("changed_files") int changedFiles,
        @JsonProperty("commits_count") int commitsCount,
        @JsonProperty("file_paths") List<String> filePaths,
        @JsonProperty("labels") List<String> labels,
        @JsonProperty("requested_reviewers") List<String> requestedReviewers,
        @JsonProperty("url") String url,
        @JsonProperty("opened_at") Instant openedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("merged_at") Instant mergedAt,
        @JsonProperty("closed_at") Instant closedAt,
        @JsonProperty("first_review_at") Instant firstReviewAt,
        @JsonProperty("approved_at") Instant approvedAt,
        @JsonProperty("repo_full_name") String repoFullName,
        @JsonProperty("score") BigDecimal score,
        @JsonProperty("tier") String tier,
        @JsonProperty("confidence") String confidence,
        @JsonProperty("features") RawJson features,
        @JsonProperty("contributions") RawJson contributions,
        @JsonProperty("rule_hits") RawJson ruleHits,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("scored_at") Instant scoredAt,
        @JsonProperty("reverted") Boolean reverted,
        @JsonProperty("hotfixed") Boolean hotfixed,
        @JsonProperty("caused_incident") Boolean causedIncident,
        @JsonProperty("outcome_notes") String outcomeNotes
) {
}
