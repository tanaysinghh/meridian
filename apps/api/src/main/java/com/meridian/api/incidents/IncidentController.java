package com.meridian.api.incidents;

import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.common.ApiException;
import com.meridian.api.common.Tuples;
import com.meridian.api.incidents.dto.IncidentDtos;
import com.meridian.api.pullrequests.PrOutcome;
import com.meridian.api.pullrequests.PrOutcomeRepository;
import com.meridian.api.pullrequests.PullRequestRepository;
import com.meridian.api.repos.RepoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /incidents} — the incident log.
 *
 * <p>Filing an incident against a PR does two things: it records the incident, and it flips that
 * PR's {@code caused_incident} outcome with a note. That second write is what closes the feedback
 * loop the risk model trains on — without it, incidents and the PRs that caused them would be
 * unrelated records.
 *
 * <p>Any member can file one. Unlike rules and settings there is no role gate, matching the previous
 * behaviour: an incident is an observation, and making people wait for a lead to record one is how
 * incidents go unrecorded.
 */
@RestController
@RequestMapping("/incidents")
public class IncidentController {

    private static final String LIST_SQL = """
            SELECT i.*, r.full_name AS repo_full_name,
                   p.number AS pr_number, p.title AS pr_title
              FROM incidents i
              LEFT JOIN repos r ON r.id = i.repo_id
              LEFT JOIN pull_requests p ON p.id = i.related_pr_id
             WHERE i.org_id = :orgId
             ORDER BY i.occurred_at DESC
            """;

    @PersistenceContext
    private EntityManager em;

    private final IncidentRepository incidents;
    private final PullRequestRepository pullRequests;
    private final PrOutcomeRepository outcomes;
    private final RepoRepository repos;

    public IncidentController(IncidentRepository incidents,
                              PullRequestRepository pullRequests,
                              PrOutcomeRepository outcomes,
                              RepoRepository repos) {
        this.incidents = incidents;
        this.pullRequests = pullRequests;
        this.outcomes = outcomes;
        this.repos = repos;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(LIST_SQL, Tuple.class)
                .setParameter("orgId", user.orgId())
                .getResultList();

        List<IncidentDtos.Summary> items = rows.stream()
                .map(t -> new IncidentDtos.Summary(
                        Tuples.uuid(t, "id"),
                        Tuples.uuid(t, "org_id"),
                        Tuples.uuid(t, "repo_id"),
                        Tuples.uuid(t, "related_pr_id"),
                        Tuples.string(t, "title"),
                        Tuples.string(t, "severity"),
                        Tuples.string(t, "description"),
                        Tuples.uuid(t, "reported_by"),
                        Tuples.instant(t, "occurred_at"),
                        Tuples.instant(t, "resolved_at"),
                        Tuples.instant(t, "created_at"),
                        Tuples.string(t, "repo_full_name"),
                        Tuples.integer(t, "pr_number"),
                        Tuples.string(t, "pr_title")))
                .toList();

        return Map.of("items", items);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody IncidentDtos.Create body) {

        // Both links must point inside the caller's org. A 400 rather than a 404 here, matching the
        // previous handler — the request itself is malformed, not the route.
        if (body.relatedPrId() != null && !pullRequests.existsInOrg(body.relatedPrId(), user.orgId())) {
            throw ApiException.badRequest("invalid_related_pr");
        }
        if (body.repoId() != null && !repos.existsByIdAndOrgId(body.repoId(), user.orgId())) {
            throw ApiException.badRequest("invalid_repo");
        }

        Instant occurredAt = body.occurredAt() != null ? body.occurredAt() : Instant.now();

        Incident incident = new Incident(user.orgId(), body.title(), body.severity(), occurredAt);
        incident.setRepoId(body.repoId());
        incident.setRelatedPrId(body.relatedPrId());
        incident.setDescription(body.descriptionOrEmpty());
        incident.setReportedBy(user.id());
        Incident saved = incidents.save(incident);

        if (body.relatedPrId() != null) {
            linkOutcome(body.relatedPrId(), body.title());
        }

        return Map.of("id", saved.getId());
    }

    /**
     * Marks the linked PR as having caused an incident, appending to any existing note rather than
     * replacing it — a PR can be implicated in more than one incident, and the earlier note is
     * still true.
     */
    private void linkOutcome(UUID prId, String incidentTitle) {
        PrOutcome outcome = outcomes.findByPrId(prId).orElseGet(() -> new PrOutcome(prId));
        String note = "Incident: " + incidentTitle;
        String existing = outcome.getOutcomeNotes();

        outcome.setCausedIncident(true);
        outcome.setOutcomeNotes(existing == null || existing.isEmpty() ? note : existing + "\n" + note);
        outcomes.save(outcome);
    }
}
