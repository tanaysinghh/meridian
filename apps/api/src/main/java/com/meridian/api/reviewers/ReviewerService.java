package com.meridian.api.reviewers;

import com.meridian.api.common.Tuples;
import com.meridian.api.pullrequests.PullRequest;
import com.meridian.api.pullrequests.PullRequestRepository;
import com.meridian.api.reviewers.dto.ReviewerLoadDto;
import com.meridian.api.reviewers.dto.ReviewerSuggestionDto;
import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reviewer load and reviewer suggestions.
 *
 * <p>The suggestion ranking is the interesting part and is carried over unchanged: candidates are
 * scored by how many commits they have under the path prefixes this PR touches, then that raw
 * ownership score is divided by {@code 1 + openReviews * 0.5}. The division is what keeps the
 * feature useful in practice — without it the same two or three people who own the busiest
 * directories would be suggested for everything, and the queue would pile up on them. The PR's own
 * author is always excluded.
 */
@Service
public class ReviewerService {

    /** Dampening applied per open review already assigned to a candidate. */
    private static final double LOAD_PENALTY = 0.5;

    private static final int MAX_SUGGESTIONS = 5;

    /**
     * Open-review counts per reviewer login. {@code unnest} over the {@code requested_reviewers}
     * array has no JPQL equivalent, so this stays native.
     */
    private static final String LOAD_SQL = """
            SELECT unnest(p.requested_reviewers) AS login, COUNT(*)::int AS open_reviews,
                   AVG(s.score)::float AS avg_risk
              FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              LEFT JOIN LATERAL (
                SELECT score FROM pr_risk_scores WHERE pr_id = p.id
                  ORDER BY scored_at DESC LIMIT 1
              ) s ON true
             WHERE r.org_id = :orgId AND p.state = 'open'
             GROUP BY login
            """;

    private static final String SUGGEST_LOAD_SQL = """
            SELECT unnest(p.requested_reviewers) AS login, COUNT(*)::int AS open_reviews
              FROM pull_requests p JOIN repos r ON r.id = p.repo_id
             WHERE r.org_id = :orgId AND p.state = 'open'
             GROUP BY login
            """;

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final PullRequestRepository pullRequests;
    private final FileOwnershipRepository ownership;

    public ReviewerService(UserRepository users,
                           PullRequestRepository pullRequests,
                           FileOwnershipRepository ownership) {
        this.users = users;
        this.pullRequests = pullRequests;
        this.ownership = ownership;
    }

    /** Every org member with a GitHub identity, annotated with their current review load. */
    @Transactional(readOnly = true)
    public List<ReviewerLoadDto> listWithLoad(UUID orgId) {
        List<User> members = users.findByOrgIdAndGithubLoginIsNotNull(orgId);

        @SuppressWarnings("unchecked")
        List<Tuple> loadRows = em.createNativeQuery(LOAD_SQL, Tuple.class)
                .setParameter("orgId", orgId)
                .getResultList();

        Map<String, Tuple> byLogin = new HashMap<>();
        for (Tuple row : loadRows) {
            byLogin.put(Tuples.string(row, "login"), row);
        }

        return members.stream()
                .map(u -> {
                    Tuple load = byLogin.get(u.getGithubLogin());
                    return new ReviewerLoadDto(
                            u.getId(),
                            u.getGithubLogin(),
                            u.getName(),
                            u.getAvatarUrl(),
                            u.getRole(),
                            load == null ? 0 : Tuples.intOrZero(load, "open_reviews"),
                            load == null ? 0.0 : Optional.ofNullable(Tuples.doubleValue(load, "avg_risk")).orElse(0.0));
                })
                // Busiest first, matching the old sort by open_reviews descending.
                .sorted(Comparator.comparingInt(ReviewerLoadDto::openReviews).reversed())
                .toList();
    }

    /**
     * Ranks candidate reviewers for one PR.
     *
     * @return up to five suggestions, best first; empty when the PR is not in this org or nothing
     *         owns the paths it touches
     */
    @Transactional(readOnly = true)
    public List<ReviewerSuggestionDto> suggest(UUID orgId, UUID prId) {
        if (!pullRequests.existsInOrg(prId, orgId)) {
            return List.of();
        }
        PullRequest pr = pullRequests.findById(prId).orElse(null);
        if (pr == null) {
            return List.of();
        }

        List<FileOwnership> owners = ownership.findByRepoId(pr.getRepoId());

        @SuppressWarnings("unchecked")
        List<Tuple> loadRows = em.createNativeQuery(SUGGEST_LOAD_SQL, Tuple.class)
                .setParameter("orgId", orgId)
                .getResultList();

        Map<String, Integer> loadByLogin = new HashMap<>();
        for (Tuple row : loadRows) {
            loadByLogin.put(Tuples.string(row, "login"), Tuples.intOrZero(row, "open_reviews"));
        }

        // Accumulate ownership weight: every commit an owner has under a prefix this PR touches.
        Map<String, Integer> ownershipScore = new LinkedHashMap<>();
        for (String path : pr.getFilePaths()) {
            if (path == null) {
                continue;
            }
            for (FileOwnership owner : owners) {
                if (path.startsWith(owner.getPathPrefix())) {
                    ownershipScore.merge(owner.getOwnerLogin(), owner.getCommits(), Integer::sum);
                }
            }
        }

        return ownershipScore.entrySet().stream()
                .filter(e -> !e.getKey().equals(pr.getAuthorLogin()))
                .map(e -> {
                    int load = loadByLogin.getOrDefault(e.getKey(), 0);
                    double finalScore = e.getValue() / (1 + load * LOAD_PENALTY);
                    return new ReviewerSuggestionDto(
                            e.getKey(),
                            e.getValue(),
                            load,
                            round2(finalScore));
                })
                .sorted(Comparator.comparingDouble(ReviewerSuggestionDto::score).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** Matches the old {@code +finalScore.toFixed(2)} — two decimals, as a number. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
