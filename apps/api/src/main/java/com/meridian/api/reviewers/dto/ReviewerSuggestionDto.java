package com.meridian.api.reviewers.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One suggested reviewer for a PR.
 *
 * <p>Both inputs are returned alongside the final {@code score} so the UI can explain the ranking
 * rather than presenting it as an oracle: "owns this code" and "already has four reviews" are the
 * two things a lead needs to see to override the suggestion sensibly.
 */
@JsonPropertyOrder({"login", "ownership_score", "open_reviews", "score"})
public record ReviewerSuggestionDto(
        @JsonProperty("login") String login,
        @JsonProperty("ownership_score") int ownershipScore,
        @JsonProperty("open_reviews") int openReviews,
        @JsonProperty("score") double score
) {
}
