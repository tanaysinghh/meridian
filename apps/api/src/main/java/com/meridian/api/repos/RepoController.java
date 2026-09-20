package com.meridian.api.repos;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.common.Tuples;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /repos} — connected repositories and their risk-threshold tuning.
 *
 * <p>Listing is open to any member of the org; changing a threshold is restricted to admins and
 * team leads, the same pair the old {@code requireRole('admin', 'team_lead')} allowed.
 */
@RestController
@RequestMapping("/repos")
public class RepoController {

    /** Per-repo summary, including the open and high-risk PR counts the list view shows. */
    @JsonPropertyOrder({"id", "full_name", "default_branch", "risk_threshold", "connected_at",
            "open_prs", "high_risk_prs"})
    public record RepoSummary(
            @JsonProperty("id") UUID id,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("risk_threshold") BigDecimal riskThreshold,
            @JsonProperty("connected_at") Instant connectedAt,
            @JsonProperty("open_prs") int openPrs,
            @JsonProperty("high_risk_prs") int highRiskPrs
    ) {}

    /** Body of {@code PATCH /repos/{id}} — the threshold is the only tunable field. */
    public record ThresholdUpdate(
            @JsonProperty("risk_threshold")
            @NotNull(message = "Required")
            @DecimalMin(value = "0", message = "Number must be greater than or equal to 0")
            @DecimalMax(value = "1", message = "Number must be less than or equal to 1")
            BigDecimal riskThreshold
    ) {}

    /**
     * Counts open PRs and, among them, those whose latest score is high or critical. The
     * {@code FILTER} clause and the LATERAL join are both Postgres-specific, so this stays native.
     */
    private static final String LIST_SQL = """
            SELECT r.id, r.full_name, r.default_branch, r.risk_threshold, r.connected_at,
                   COUNT(p.id)::int AS open_prs,
                   COUNT(p.id) FILTER (WHERE s.tier IN ('high','critical'))::int AS high_risk_prs
              FROM repos r
              LEFT JOIN pull_requests p ON p.repo_id = r.id AND p.state = 'open'
              LEFT JOIN LATERAL (
                SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId
             GROUP BY r.id
             ORDER BY r.full_name
            """;

    @PersistenceContext
    private EntityManager em;

    private final RepoRepository repos;

    public RepoController(RepoRepository repos) {
        this.repos = repos;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(LIST_SQL, Tuple.class)
                .setParameter("orgId", user.orgId())
                .getResultList();

        List<RepoSummary> items = rows.stream()
                .map(t -> new RepoSummary(
                        Tuples.uuid(t, "id"),
                        Tuples.string(t, "full_name"),
                        Tuples.string(t, "default_branch"),
                        Tuples.decimal(t, "risk_threshold"),
                        Tuples.instant(t, "connected_at"),
                        Tuples.intOrZero(t, "open_prs"),
                        Tuples.intOrZero(t, "high_risk_prs")))
                .toList();

        return Map.of("items", items);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_LEAD')")
    @Transactional
    public Map<String, Object> updateThreshold(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody ThresholdUpdate body) {
        // Scoped by org id: a repo belonging to someone else matches nothing and this is a no-op,
        // which is what the previous UPDATE ... WHERE org_id = $3 also did.
        repos.updateRiskThreshold(id, user.orgId(), body.riskThreshold());
        return Map.of("ok", true);
    }
}
