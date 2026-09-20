package com.meridian.api.users;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

/**
 * The authenticated user as the frontend sees it, on {@code /me} and in the {@code /auth/login}
 * response.
 *
 * <p>Field-for-field the row the old queries selected — {@code id, org_id, email, name, role,
 * github_login, avatar_url} — and nothing more. There is deliberately no slot for
 * {@code password_hash}: the old login handler had to {@code delete user.password_hash} before
 * responding, and this type makes that impossible to forget.
 */
@JsonPropertyOrder({"id", "org_id", "email", "name", "role", "github_login", "avatar_url"})
public record UserDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("org_id") UUID orgId,
        @JsonProperty("email") String email,
        @JsonProperty("name") String name,
        @JsonProperty("role") Role role,
        @JsonProperty("github_login") String githubLogin,
        @JsonProperty("avatar_url") String avatarUrl
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getOrgId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getGithubLogin(),
                user.getAvatarUrl());
    }
}
