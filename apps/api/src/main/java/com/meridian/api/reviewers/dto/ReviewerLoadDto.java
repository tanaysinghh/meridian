package com.meridian.api.reviewers.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.users.Role;

import java.util.UUID;

/**
 * A row in {@code GET /reviewers}: an org member plus how much review work is currently on them.
 *
 * <p>{@code avg_risk} is the mean risk score across their open reviews — a reviewer with three
 * critical PRs is carrying more than one with three trivial ones, and the UI weights the two
 * differently.
 */
@JsonPropertyOrder({"id", "github_login", "name", "avatar_url", "role", "open_reviews", "avg_risk"})
public record ReviewerLoadDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("github_login") String githubLogin,
        @JsonProperty("name") String name,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("role") Role role,
        @JsonProperty("open_reviews") int openReviews,
        @JsonProperty("avg_risk") double avgRisk
) {
}
