package com.meridian.api.auth;

import com.meridian.api.users.Role;
import com.meridian.api.users.User;

import java.util.UUID;

/**
 * The caller, as every controller below the security filter sees them.
 *
 * <p>Stands in for {@code req.user}. It is populated the same way that was: the access token
 * identifies a user id, and the row is then loaded from the database on each request so a role
 * change or a deleted account takes effect immediately rather than at the next token refresh.
 */
public record AuthenticatedUser(
        UUID id,
        UUID orgId,
        String email,
        String name,
        Role role,
        String githubLogin,
        String avatarUrl
) {
    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(
                user.getId(),
                user.getOrgId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getGithubLogin(),
                user.getAvatarUrl());
    }
}
