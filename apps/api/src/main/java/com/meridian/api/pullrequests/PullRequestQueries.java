package com.meridian.api.pullrequests;

import com.meridian.api.common.Tuples;
import com.meridian.api.pullrequests.dto.PrDetailDto;
import com.meridian.api.pullrequests.dto.PrEventDto;
import com.meridian.api.pullrequests.dto.PrSummaryDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read queries for the PR views.
 *
 * <p>These stay as native SQL, carried over from the Express handlers unchanged. The reason is the
 * {@code LEFT JOIN LATERAL ... ORDER BY scored_at DESC LIMIT 1} that every one of them uses to pick
 * a PR's most recent score: it is the correct and efficient way to express "the latest row per
 * group" in Postgres, and JPQL cannot express it at all. Rewriting it as a subquery-per-column or
 * fetching all scores and filtering in Java would be slower and less obviously correct.
 *
 * <p>Filtering in {@link #list} is done with {@code (cast(:param as text) is null or ...)} rather
 * than by concatenating SQL, so the shape of the statement does not depend on which filters the
 * caller supplied and no value ever reaches the parser.
 */
@Repository
public class PullRequestQueries {

    @PersistenceContext
    private EntityManager em;

    private static final String LIST_SQL = """
            SELECT p.id, p.number, p.title, p.author_login, p.author_avatar, p.state,
                   p.additions, p.deletions, p.changed_files, p.commits_count,
                   p.labels, p.requested_reviewers, p.url, p.opened_at, p.updated_at, p.merged_at,
                   r.full_name AS repo_full_name,
                   s.score, s.tier, s.confidence, s.rule_hits
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT score, tier, confidence, rule_hits
                  FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId
               AND (cast(:state  as text) IS NULL OR p.state = cast(:state as text))
               AND (cast(:repo   as text) IS NULL OR r.full_name = cast(:repo as text))
               AND (cast(:author as text) IS NULL OR p.author_login = cast(:author as text))
               AND (cast(:tier   as text) IS NULL OR s.tier = cast(:tier as text))
             ORDER BY p.updated_at DESC
             LIMIT :maxResults
            """;

    private static final String DETAIL_SQL = """
            SELECT p.*, r.full_name AS repo_full_name,
                   s.score, s.tier, s.confidence, s.features, s.contributions,
                   s.rule_hits, s.model_version, s.scored_at,
                   o.reverted, o.hotfixed, o.caused_incident, o.outcome_notes
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT * FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
              LEFT JOIN pr_outcomes o ON o.pr_id = p.id
             WHERE p.id = :prId AND r.org_id = :orgId
            """;

    private static final String EVENTS_SQL = """
            SELECT event_type, actor_login, occurred_at, payload
              FROM pr_events WHERE pr_id = :prId
             ORDER BY occurred_at DESC LIMIT 200
            """;

    @Transactional(readOnly = true)
    public List<PrSummaryDto> list(UUID orgId, String state, String repo, String author, String tier, int limit) {
        Query query = em.createNativeQuery(LIST_SQL, Tuple.class)
                .setParameter("orgId", orgId)
                .setParameter("state", state)
                .setParameter("repo", repo)
                .setParameter("author", author)
                .setParameter("tier", tier)
                .setParameter("maxResults", limit);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(PullRequestQueries::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public Optional<PrDetailDto> findDetail(UUID prId, UUID orgId) {
        Query query = em.createNativeQuery(DETAIL_SQL, Tuple.class)
                .setParameter("prId", prId)
                .setParameter("orgId", orgId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().findFirst().map(PullRequestQueries::toDetail);
    }

    @Transactional(readOnly = true)
    public List<PrEventDto> events(UUID prId) {
        Query query = em.createNativeQuery(EVENTS_SQL, Tuple.class)
                .setParameter("prId", prId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(t -> new PrEventDto(
                        Tuples.string(t, "event_type"),
                        Tuples.string(t, "actor_login"),
                        Tuples.instant(t, "occurred_at"),
                        Tuples.json(t, "payload")))
                .toList();
    }

    private static PrSummaryDto toSummary(Tuple t) {
        return new PrSummaryDto(
                Tuples.uuid(t, "id"),
                Tuples.intOrZero(t, "number"),
                Tuples.string(t, "title"),
                Tuples.string(t, "author_login"),
                Tuples.string(t, "author_avatar"),
                Tuples.string(t, "state"),
                Tuples.intOrZero(t, "additions"),
                Tuples.intOrZero(t, "deletions"),
                Tuples.intOrZero(t, "changed_files"),
                Tuples.intOrZero(t, "commits_count"),
                Tuples.stringList(t, "labels"),
                Tuples.stringList(t, "requested_reviewers"),
                Tuples.string(t, "url"),
                Tuples.instant(t, "opened_at"),
                Tuples.instant(t, "updated_at"),
                Tuples.instant(t, "merged_at"),
                Tuples.string(t, "repo_full_name"),
                Tuples.decimal(t, "score"),
                Tuples.string(t, "tier"),
                Tuples.string(t, "confidence"),
                Tuples.json(t, "rule_hits"));
    }

    private static PrDetailDto toDetail(Tuple t) {
        return new PrDetailDto(
                Tuples.uuid(t, "id"),
                Tuples.uuid(t, "repo_id"),
                Tuples.intOrZero(t, "number"),
                Tuples.longValue(t, "github_id"),
                Tuples.string(t, "title"),
                Tuples.string(t, "body"),
                Tuples.string(t, "author_login"),
                Tuples.string(t, "author_avatar"),
                Tuples.string(t, "state"),
                Boolean.TRUE.equals(Tuples.bool(t, "draft")),
                Tuples.string(t, "base_ref"),
                Tuples.string(t, "head_ref"),
                Tuples.intOrZero(t, "additions"),
                Tuples.intOrZero(t, "deletions"),
                Tuples.intOrZero(t, "changed_files"),
                Tuples.intOrZero(t, "commits_count"),
                Tuples.stringList(t, "file_paths"),
                Tuples.stringList(t, "labels"),
                Tuples.stringList(t, "requested_reviewers"),
                Tuples.string(t, "url"),
                Tuples.instant(t, "opened_at"),
                Tuples.instant(t, "updated_at"),
                Tuples.instant(t, "merged_at"),
                Tuples.instant(t, "closed_at"),
                Tuples.instant(t, "first_review_at"),
                Tuples.instant(t, "approved_at"),
                Tuples.string(t, "repo_full_name"),
                Tuples.decimal(t, "score"),
                Tuples.string(t, "tier"),
                Tuples.string(t, "confidence"),
                Tuples.json(t, "features"),
                Tuples.json(t, "contributions"),
                Tuples.json(t, "rule_hits"),
                Tuples.string(t, "model_version"),
                Tuples.instant(t, "scored_at"),
                Tuples.bool(t, "reverted"),
                Tuples.bool(t, "hotfixed"),
                Tuples.bool(t, "caused_incident"),
                Tuples.string(t, "outcome_notes"));
    }
}
