package com.meridian.api.reviewers;

import com.meridian.api.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * {@code /reviewers} — who is available to review, and who should review a given PR.
 */
@RestController
@RequestMapping("/reviewers")
public class ReviewerController {

    private final ReviewerService service;

    public ReviewerController(ReviewerService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("items", service.listWithLoad(user.orgId()));
    }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam("pr_id") UUID prId) {
        return Map.of("suggestions", service.suggest(user.orgId(), prId));
    }
}
