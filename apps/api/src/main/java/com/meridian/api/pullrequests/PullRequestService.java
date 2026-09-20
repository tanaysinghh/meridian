package com.meridian.api.pullrequests;

import com.meridian.api.common.ApiException;
import com.meridian.api.pullrequests.dto.OutcomeRequest;
import com.meridian.api.pullrequests.dto.PrDetailDto;
import com.meridian.api.pullrequests.dto.PrEventDto;
import com.meridian.api.pullrequests.dto.PrSummaryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read and outcome-recording operations for pull requests.
 *
 * <p>Every method takes the caller's org id and scopes on it. That is the tenancy boundary: a PR id
 * from another org resolves to nothing and surfaces as a 404, which is deliberate — a 403 would
 * confirm the id exists.
 */
@Service
public class PullRequestService {

    private final PullRequestQueries queries;
    private final PullRequestRepository pullRequests;
    private final PrOutcomeRepository outcomes;

    public PullRequestService(PullRequestQueries queries,
                              PullRequestRepository pullRequests,
                              PrOutcomeRepository outcomes) {
        this.queries = queries;
        this.pullRequests = pullRequests;
        this.outcomes = outcomes;
    }

    @Transactional(readOnly = true)
    public List<PrSummaryDto> list(UUID orgId, String state, String repo, String author, String tier, int limit) {
        return queries.list(orgId, state, repo, author, tier, limit);
    }

    @Transactional(readOnly = true)
    public PrDetailDto detail(UUID prId, UUID orgId) {
        return queries.findDetail(prId, orgId).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<PrEventDto> events(UUID prId) {
        return queries.events(prId);
    }

    /**
     * Records or replaces the outcome for a PR.
     *
     * <p>Upsert semantics, matching the old {@code ON CONFLICT (pr_id) DO UPDATE}: submitting again
     * overwrites all four fields rather than merging, so the form always reflects what was sent.
     * {@code observed_at} is bumped on every write.
     */
    @Transactional
    public void recordOutcome(UUID prId, UUID orgId, OutcomeRequest request) {
        if (!pullRequests.existsInOrg(prId, orgId)) {
            throw ApiException.notFound();
        }

        PrOutcome outcome = outcomes.findByPrId(prId).orElseGet(() -> new PrOutcome(prId));
        outcome.setReverted(request.revertedOrFalse());
        outcome.setHotfixed(request.hotfixedOrFalse());
        outcome.setCausedIncident(request.causedIncidentOrFalse());
        outcome.setOutcomeNotes(request.notes());
        outcome.setObservedAt(Instant.now());
        outcomes.save(outcome);
    }
}
