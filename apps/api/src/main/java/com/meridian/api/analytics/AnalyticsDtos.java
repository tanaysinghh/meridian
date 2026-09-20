package com.meridian.api.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response shapes for the analytics endpoints, kept together because each is a handful of fields
 * that exists only to name the columns of one aggregate query.
 *
 * <p>Field names and nullability follow the previous responses exactly. Where the old SQL cast an
 * average to {@code ::float}, the type here is a boxed {@link Double} — those averages are null, not
 * zero, when the window contains no rows, and the charts treat the two differently.
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** One bar of the open-PR tier distribution. */
    @JsonPropertyOrder({"tier", "n"})
    public record TierCount(
            @JsonProperty("tier") String tier,
            @JsonProperty("n") int n
    ) {}

    /** Merges per day over the last 30 days. */
    @JsonPropertyOrder({"day", "merged"})
    public record ThroughputPoint(
            @JsonProperty("day") Instant day,
            @JsonProperty("merged") int merged
    ) {}

    /** A row of the "recently touched" list on the overview. */
    @JsonPropertyOrder({"id", "number", "title", "author_login", "updated_at", "repo_full_name", "tier", "score"})
    public record RecentPr(
            @JsonProperty("id") UUID id,
            @JsonProperty("number") int number,
            @JsonProperty("title") String title,
            @JsonProperty("author_login") String authorLogin,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("repo_full_name") String repoFullName,
            @JsonProperty("tier") String tier,
            @JsonProperty("score") BigDecimal score
    ) {}

    /** PR size, bucketed by week. */
    @JsonPropertyOrder({"week", "avg_size", "median_size", "prs"})
    public record SizePoint(
            @JsonProperty("week") Instant week,
            @JsonProperty("avg_size") Integer avgSize,
            @JsonProperty("median_size") Integer medianSize,
            @JsonProperty("prs") int prs
    ) {}

    /** Average hours spent in each stage of review, over the last 30 days of merges. */
    @JsonPropertyOrder({"hours_to_first_review", "hours_first_review_to_approve", "hours_approve_to_merge"})
    public record CycleTime(
            @JsonProperty("hours_to_first_review") Double hoursToFirstReview,
            @JsonProperty("hours_first_review_to_approve") Double hoursFirstReviewToApprove,
            @JsonProperty("hours_approve_to_merge") Double hoursApproveToMerge
    ) {}

    /**
     * One cell of the merge-time heatmap: ISO day-of-week 1..7, hour 0..23.
     *
     * <p>This is the view that answers "are we merging our riskiest changes late on a Friday?".
     */
    @JsonPropertyOrder({"dow", "hour", "merges", "avg_risk"})
    public record HeatmapCell(
            @JsonProperty("dow") int dow,
            @JsonProperty("hour") int hour,
            @JsonProperty("merges") int merges,
            @JsonProperty("avg_risk") Double avgRisk
    ) {}

    /** Merges and reverts per week. */
    @JsonPropertyOrder({"week", "merged", "reverted"})
    public record RevertPoint(
            @JsonProperty("week") Instant week,
            @JsonProperty("merged") int merged,
            @JsonProperty("reverted") int reverted
    ) {}

    /** Per-author activity over the last 90 days. Framed constructively in the UI. */
    @JsonPropertyOrder({"author_login", "prs", "avg_risk", "reverted"})
    public record AuthorTrend(
            @JsonProperty("author_login") String authorLogin,
            @JsonProperty("prs") int prs,
            @JsonProperty("avg_risk") Double avgRisk,
            @JsonProperty("reverted") int reverted
    ) {}
}
