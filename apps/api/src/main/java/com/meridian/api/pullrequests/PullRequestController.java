package com.meridian.api.pullrequests;

import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.pullrequests.dto.OutcomeRequest;
import com.meridian.api.pullrequests.dto.PrDetailDto;
import com.meridian.api.pullrequests.dto.PrEventDto;
import com.meridian.api.pullrequests.dto.PrSummaryDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /prs} — the PR list, a single PR with its event history, and outcome recording.
 *
 * <p>The query parameters on the list endpoint are constrained to the same enumerations the Zod
 * schema used, so an unknown {@code state} or {@code tier} is a 400 rather than silently matching
 * nothing. {@code limit} keeps its 1..500 bound and its default of 100.
 */
@RestController
@RequestMapping("/prs")
@Validated
public class PullRequestController {

    private final PullRequestService service;

    public PullRequestController(PullRequestService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false)
            @Pattern(regexp = "open|closed|merged", message = "Invalid enum value") String state,
            @RequestParam(required = false) @Size(max = 200) String repo,
            @RequestParam(required = false)
            @Pattern(regexp = "low|medium|high|critical", message = "Invalid enum value") String tier,
            @RequestParam(required = false) @Size(max = 200) String author,
            @RequestParam(defaultValue = "100")
            @Positive(message = "Number must be greater than 0")
            @Max(value = 500, message = "Number must be less than or equal to 500")
            int limit) {

        List<PrSummaryDto> items = service.list(user.orgId(), state, repo, author, tier, limit);
        return Map.of("items", items);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID id) {
        PrDetailDto pr = service.detail(id, user.orgId());
        List<PrEventDto> events = service.events(id);

        // LinkedHashMap rather than Map.of: the response key order is part of what the previous
        // implementation emitted, and Map.of does not preserve it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pr", pr);
        body.put("events", events);
        return body;
    }

    @PostMapping("/{id}/outcome")
    public Map<String, Object> recordOutcome(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody(required = false) OutcomeRequest body) {
        OutcomeRequest request = body != null
                ? body
                : new OutcomeRequest(false, false, false, null);
        service.recordOutcome(id, user.orgId(), request);
        return Map.of("ok", true);
    }
}
