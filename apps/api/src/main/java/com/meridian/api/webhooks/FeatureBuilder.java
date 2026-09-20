package com.meridian.api.webhooks;

import com.meridian.api.pullrequests.FileHotness;
import com.meridian.api.pullrequests.FileHotnessRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the feature vector handed to the risk model.
 *
 * <p>A direct port of {@code buildFeatures}, and the key ordering is preserved because the vector is
 * stored verbatim in {@code pr_risk_scores.features} and rendered in the UI's explanation panel.
 *
 * <p>Two features are derived rather than read off the payload:
 * <ul>
 *   <li>{@code hot_file_overlap} sums the hotness score of every tracked path this PR's files sit
 *       under — a change to code that churns and breaks often scores higher than the same change
 *       elsewhere;</li>
 *   <li>{@code author_revert_rate} is the author's historical reverts over their total PRs, which is
 *       why the author history query spans all repos rather than just this one.</li>
 * </ul>
 */
@Component
public class FeatureBuilder {

    private static final Pattern AUTH_PATHS = Pattern.compile("auth|session|token|permission", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILLING_PATHS = Pattern.compile("billing|payment|invoice", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFRA_PATHS = Pattern.compile("terraform|infra|kubernetes|helm", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PATHS = Pattern.compile("secret|\\.env|credential", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONVENTIONAL_COMMIT =
            Pattern.compile("^(feat|fix|chore|docs|refactor|test|perf|build|ci)(\\(.+\\))?:");
    private static final Pattern LOW_EFFORT_COMMIT =
            Pattern.compile("^(wip|fix|stuff|misc|.)$", Pattern.CASE_INSENSITIVE);

    private static final String AUTHOR_HISTORY_SQL = """
            SELECT COUNT(*)::int AS prs,
                   COUNT(*) FILTER (WHERE o.reverted)::int AS reverts
              FROM pull_requests p LEFT JOIN pr_outcomes o ON o.pr_id = p.id
             WHERE p.author_login = :authorLogin
            """;

    @PersistenceContext
    private EntityManager em;

    private final FileHotnessRepository hotness;

    public FeatureBuilder(FileHotnessRepository hotness) {
        this.hotness = hotness;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> build(UUID repoId,
                                     String authorLogin,
                                     List<String> filePaths,
                                     int additions,
                                     int deletions,
                                     int changedFiles,
                                     int commitsCount,
                                     Instant openedAt,
                                     List<String> commitMessages) {

        @SuppressWarnings("unchecked")
        List<Tuple> history = em.createNativeQuery(AUTHOR_HISTORY_SQL, Tuple.class)
                .setParameter("authorLogin", authorLogin)
                .getResultList();

        int authorPrCount = 0;
        int authorReverts = 0;
        if (!history.isEmpty()) {
            authorPrCount = com.meridian.api.common.Tuples.intOrZero(history.get(0), "prs");
            authorReverts = com.meridian.api.common.Tuples.intOrZero(history.get(0), "reverts");
        }

        double hotOverlap = 0;
        for (FileHotness hot : hotness.findByRepoId(repoId)) {
            for (String path : filePaths) {
                if (path != null && path.startsWith(hot.getPath())) {
                    hotOverlap += hot.getScore() == null ? 0 : hot.getScore().doubleValue();
                    // The original summed one hotness entry per file path, taking the first match.
                    break;
                }
            }
        }

        ZonedDateTime opened = (openedAt == null ? Instant.now() : openedAt).atZone(ZoneOffset.UTC);

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("additions", additions);
        features.put("deletions", deletions);
        features.put("changed_files", changedFiles);
        features.put("commits_count", commitsCount);
        features.put("touches_auth", matches(filePaths, AUTH_PATHS));
        features.put("touches_billing", matches(filePaths, BILLING_PATHS));
        features.put("touches_infra", matches(filePaths, INFRA_PATHS));
        features.put("touches_secret", matches(filePaths, SECRET_PATHS));
        features.put("hot_file_overlap",
                BigDecimal.valueOf(hotOverlap).setScale(3, RoundingMode.HALF_UP).doubleValue());
        features.put("commit_msg_quality", commitMessageQuality(commitMessages));
        features.put("author_pr_count", authorPrCount);
        features.put("author_revert_rate", authorPrCount > 0 ? (double) authorReverts / authorPrCount : 0.0);
        // Time-of-day and day-of-week in UTC. "Merged late on a Friday" is a real risk signal.
        features.put("opened_hour", opened.getHour());
        features.put("opened_dow", opened.getDayOfWeek().getValue() % 7);
        return features;
    }

    /** 1 or 0 — the model expects numeric flags, not booleans. */
    private static int matches(List<String> paths, Pattern pattern) {
        return paths.stream().anyMatch(p -> p != null && pattern.matcher(p).find()) ? 1 : 0;
    }

    /**
     * A crude proxy for how carefully a change was put together: conventional-commit prefixes and
     * a reasonable subject line score up, bare "wip"/"fix" messages score down. Averaged over the
     * PR's commits; an empty list is neutral at 0.5.
     */
    static double commitMessageQuality(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0.5;
        }
        double total = 0;
        for (String message : messages) {
            String subject = (message == null ? "" : message).split("\n", 2)[0].trim();
            double s = 0.5;
            if (CONVENTIONAL_COMMIT.matcher(subject).find()) {
                s += 0.3;
            }
            if (subject.length() >= 20) {
                s += 0.1;
            }
            if (LOW_EFFORT_COMMIT.matcher(subject).matches()) {
                s -= 0.3;
            }
            total += Math.max(0, Math.min(1, s));
        }
        return total / messages.size();
    }

    /** Pulls commit subjects out of a webhook payload's optional {@code commits_details} array. */
    static List<String> commitMessages(JsonNode pullRequest) {
        JsonNode details = pullRequest.path("commits_details");
        if (!details.isArray()) {
            return List.of();
        }
        return details.valueStream()
                .map(node -> node.path("message").asString(""))
                .toList();
    }
}
