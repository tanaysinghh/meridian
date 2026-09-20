package com.meridian.api.users;

import com.meridian.api.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /me} — who the caller is.
 *
 * <p>The frontend calls this on every page load to decide whether to show the app or bounce to
 * login, so the response is the same {@code {"user": {...}}} envelope as before. It doubles as the
 * request that seeds the {@code mrd_csrf} cookie for the session's first mutation.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    @GetMapping
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("user", new UserDto(
                user.id(),
                user.orgId(),
                user.email(),
                user.name(),
                user.role(),
                user.githubLogin(),
                user.avatarUrl()));
    }
}
