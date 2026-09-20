package com.meridian.api.users;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

/**
 * A user as listed on the settings page's team roster.
 *
 * <p>Distinct from {@link UserDto} only in that it omits {@code org_id} — the old
 * {@code GET /settings} query did not select it, and every member of that list belongs to the
 * caller's org by construction. Kept as its own type so the absent key stays absent rather than
 * becoming an explicit {@code null}.
 */
@JsonPropertyOrder({"id", "email", "name", "role", "github_login", "avatar_url"})
public record OrgMemberDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("email") String email,
        @JsonProperty("name") String name,
        @JsonProperty("role") Role role,
        @JsonProperty("github_login") String githubLogin,
        @JsonProperty("avatar_url") String avatarUrl
) {
    public static OrgMemberDto from(User user) {
        return new OrgMemberDto(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getGithubLogin(),
                user.getAvatarUrl());
    }
}
