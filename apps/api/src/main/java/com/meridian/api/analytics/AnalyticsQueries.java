package com.meridian.api.analytics;

import com.meridian.api.analytics.AnalyticsDtos.AuthorTrend;
import com.meridian.api.analytics.AnalyticsDtos.CycleTime;
import com.meridian.api.analytics.AnalyticsDtos.HeatmapCell;
import com.meridian.api.analytics.AnalyticsDtos.RecentPr;
import com.meridian.api.analytics.AnalyticsDtos.RevertPoint;
import com.meridian.api.analytics.AnalyticsDtos.SizePoint;
import com.meridian.api.analytics.AnalyticsDtos.ThroughputPoint;
import com.meridian.api.analytics.AnalyticsDtos.TierCount;
import com.meridian.api.common.Tuples;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The analytics aggregates, as native Postgres SQL.
 *
 * <p>Carried over statement-for-statement from the Express handlers. Every one of these leans on
 * something JPQL cannot express — {@code date_trunc}, {@code PERCENTILE_CONT ... WITHIN GROUP},
 * {@code EXTRACT(ISODOW ...)}, interval arithmetic against a column
 * ({@code (os.high_risk_sla_hours || ' hours')::interval}), or the {@code LATERAL} join that picks
 * each PR's latest score. Keeping the SQL verbatim also means the numbers on the dashboard cannot
 * drift during the migration: same query, same plan, same result.
 */
@Repository
public class AnalyticsQueries {

    @PersistenceContext
    private EntityManager em;

    // --- overview ------------------------------------------------------------

    private static final String TIERS_SQL = """
            SELECT COALESCE(s.tier, 'low') AS tier, COUNT(*)::int AS n
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId AND p.state = 'open'
             GROUP BY 1
            """;

    private static final String THROUGHPUT_SQL = """
            SELECT date_trunc('day', p.merged_at) AS day, COUNT(*)::int AS merged
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
             WHERE r.org_id = :orgId AND p.merged_at IS NOT NULL
               AND p.merged_at > now() - interval '30 days'
             GROUP BY 1 ORDER BY 1
            """;

    /**
     * Open high/critical PRs that have been waiting longer than the org's configured SLA. The SLA
     * is a column, so the interval has to be built from it at query time.
     */
    private static final String SLA_SQL = """
            SELECT COUNT(*)::int AS breached
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              JOIN LATERAL (
                SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
              JOIN org_settings os ON os.org_id = r.org_id
             WHERE r.org_id = :orgId AND p.state = 'open'
               AND s.tier IN ('high','critical')
               AND p.opened_at < now() - (os.high_risk_sla_hours || ' hours')::interval
            """;

    private static final String RECENT_SQL = """
            SELECT p.id, p.number, p.title, p.author_login, p.updated_at,
                   r.full_name AS repo_full_name, s.tier, s.score
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT tier, score FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId
             ORDER BY p.updated_at DESC LIMIT 8
            """;

    // --- trends --------------------------------------------------------------

    private static final String SIZE_TREND_SQL = """
            SELECT date_trunc('week', p.opened_at) AS week,
                   AVG(p.additions + p.deletions)::int AS avg_size,
                   PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY p.additions + p.deletions)::int AS median_size,
                   COUNT(*)::int AS prs
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
             WHERE r.org_id = :orgId AND p.opened_at > now() - interval '12 weeks'
             GROUP BY 1 ORDER BY 1
            """;

    private static final String CYCLE_TIME_SQL = """
            SELECT
              AVG(EXTRACT(EPOCH FROM (first_review_at - opened_at)) / 3600)::float AS hours_to_first_review,
              AVG(EXTRACT(EPOCH FROM (approved_at - first_review_at)) / 3600)::float AS hours_first_review_to_approve,
              AVG(EXTRACT(EPOCH FROM (merged_at - approved_at)) / 3600)::float AS hours_approve_to_merge
            FROM pull_requests p
            JOIN repos r ON r.id = p.repo_id
           WHERE r.org_id = :orgId AND merged_at IS NOT NULL
             AND merged_at > now() - interval '30 days'
            """;

    private static final String HEATMAP_SQL = """
            SELECT EXTRACT(ISODOW FROM merged_at)::int AS dow,
                   EXTRACT(HOUR FROM merged_at)::int AS hour,
                   COUNT(*)::int AS merges,
                   AVG(s.score)::float AS avg_risk
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT score FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId AND merged_at IS NOT NULL
               AND merged_at > now() - interval '90 days'
             GROUP BY 1,2
            """;

    private static final String REVERT_RATE_SQL = """
            SELECT date_trunc('week', p.merged_at) AS week,
                   COUNT(*)::int AS merged,
                   SUM(CASE WHEN o.reverted THEN 1 ELSE 0 END)::int AS reverted
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN pr_outcomes o ON o.pr_id = p.id
             WHERE r.org_id = :orgId AND p.merged_at IS NOT NULL
               AND p.merged_at > now() - interval '12 weeks'
             GROUP BY 1 ORDER BY 1
            """;

    private static final String AUTHOR_TRENDS_SQL = """
            SELECT p.author_login,
                   COUNT(*)::int AS prs,
                   AVG(s.score)::float AS avg_risk,
                   SUM(CASE WHEN o.reverted THEN 1 ELSE 0 END)::int AS reverted
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT score FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
              LEFT JOIN pr_outcomes o ON o.pr_id = p.id
             WHERE r.org_id = :orgId AND p.opened_at > now() - interval '90 days'
             GROUP BY p.author_login
             ORDER BY prs DESC
            """;

    @Transactional(readOnly = true)
    public List<TierCount> tierDistribution(UUID orgId) {
        return rows(TIERS_SQL, orgId).stream()
                .map(t -> new TierCount(Tuples.string(t, "tier"), Tuples.intOrZero(t, "n")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ThroughputPoint> throughput30d(UUID orgId) {
        return rows(THROUGHPUT_SQL, orgId).stream()
                .map(t -> new ThroughputPoint(Tuples.instant(t, "day"), Tuples.intOrZero(t, "merged")))
                .toList();
    }

    @Transactional(readOnly = true)
    public int slaBreaches(UUID orgId) {
        return rows(SLA_SQL, orgId).stream()
                .findFirst()
                .map(t -> Tuples.intOrZero(t, "breached"))
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public List<RecentPr> recent(UUID orgId) {
        return rows(RECENT_SQL, orgId).stream()
                .map(t -> new RecentPr(
                        Tuples.uuid(t, "id"),
                        Tuples.intOrZero(t, "number"),
                        Tuples.string(t, "title"),
                        Tuples.string(t, "author_login"),
                        Tuples.instant(t, "updated_at"),
                        Tuples.string(t, "repo_full_name"),
                        Tuples.string(t, "tier"),
                        Tuples.decimal(t, "score")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SizePoint> sizeTrend(UUID orgId) {
        return rows(SIZE_TREND_SQL, orgId).stream()
                .map(t -> new SizePoint(
                        Tuples.instant(t, "week"),
                        Tuples.integer(t, "avg_size"),
                        Tuples.integer(t, "median_size"),
                        Tuples.intOrZero(t, "prs")))
                .toList();
    }

    @Transactional(readOnly = true)
    public CycleTime cycleTime(UUID orgId) {
        return rows(CYCLE_TIME_SQL, orgId).stream()
                .findFirst()
                .map(t -> new CycleTime(
                        Tuples.doubleValue(t, "hours_to_first_review"),
                        Tuples.doubleValue(t, "hours_first_review_to_approve"),
                        Tuples.doubleValue(t, "hours_approve_to_merge")))
                .orElse(new CycleTime(null, null, null));
    }

    @Transactional(readOnly = true)
    public List<HeatmapCell> mergeHeatmap(UUID orgId) {
        return rows(HEATMAP_SQL, orgId).stream()
                .map(t -> new HeatmapCell(
                        Tuples.intOrZero(t, "dow"),
                        Tuples.intOrZero(t, "hour"),
                        Tuples.intOrZero(t, "merges"),
                        Tuples.doubleValue(t, "avg_risk")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevertPoint> revertRate(UUID orgId) {
        return rows(REVERT_RATE_SQL, orgId).stream()
                .map(t -> new RevertPoint(
                        Tuples.instant(t, "week"),
                        Tuples.intOrZero(t, "merged"),
                        Tuples.intOrZero(t, "reverted")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuthorTrend> authorTrends(UUID orgId) {
        return rows(AUTHOR_TRENDS_SQL, orgId).stream()
                .map(t -> new AuthorTrend(
                        Tuples.string(t, "author_login"),
                        Tuples.intOrZero(t, "prs"),
                        Tuples.doubleValue(t, "avg_risk"),
                        Tuples.intOrZero(t, "reverted")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> rows(String sql, UUID orgId) {
        Query query = em.createNativeQuery(sql, Tuple.class).setParameter("orgId", orgId);
        return query.getResultList();
    }
}
