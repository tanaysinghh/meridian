package com.meridian.api.webhooks;

import com.meridian.api.common.Tier;
import com.meridian.api.orgs.OrgRepository;
import com.meridian.api.orgs.OrgSettings;
import com.meridian.api.orgs.OrgSettingsRepository;
import com.meridian.api.pullrequests.PrEvent;
import com.meridian.api.pullrequests.PrEventRepository;
import com.meridian.api.pullrequests.PrRiskScore;
import com.meridian.api.pullrequests.PrRiskScoreRepository;
import com.meridian.api.pullrequests.PullRequest;
import com.meridian.api.pullrequests.PullRequestRepository;
import com.meridian.api.realtime.RealtimeGateway;
import com.meridian.api.repos.Repo;
import com.meridian.api.repos.RepoRepository;
import com.meridian.api.rules.RepoRule;
import com.meridian.api.rules.RepoRuleRepository;
import com.meridian.api.rules.RuleEvaluator;
import com.meridian.api.integrations.SlackNotifier;
import com.meridian.api.ml.MlClient;
import com.meridian.api.ml.MlScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a verified GitHub delivery into stored state, a risk score, and a dashboard event.
 *
 * <p>The flow, unchanged from the Express handler:
 *
 * <ol>
 *   <li>Upsert the repo and the PR, and append the raw delivery to {@code pr_events}. This is one
 *       transaction — a half-ingested PR with no event trail would be worse than none.</li>
 *   <li>Respond. The HTTP response does not wait for scoring, because GitHub's delivery timeout is
 *       short and a slow model must not cause a redelivery storm.</li>
 *   <li>Score asynchronously: build features, ask the ML service (falling back to the heuristic if
 *       it is down), apply rules, and take {@code max(ruleTier, modelTier)}.</li>
 * </ol>
 *
 * <p>Scoring failures are logged and dropped, never retried into the response. The PR is still
 * ingested and will be rescored on the next {@code synchronize}.
 */
@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    /** Actions that warrant a fresh score. A closed or assigned PR does not change its risk. */
    private static final List<String> SCORING_ACTIONS =
            List.of("opened", "synchronize", "reopened", "edited", "ready_for_review");

    private final RepoRepository repos;
    private final PullRequestRepository pullRequests;
    private final PrEventRepository events;
    private final PrRiskScoreRepository scores;
    private final RepoRuleRepository rules;
    private final OrgRepository orgs;
    private final OrgSettingsRepository orgSettings;
    private final FeatureBuilder featureBuilder;
    private final MlClient mlClient;
    private final RuleEvaluator ruleEvaluator;
    private final RealtimeGateway realtime;
    private final SlackNotifier slack;
    private final ObjectMapper objectMapper;

    public WebhookIngestService(RepoRepository repos,
                                PullRequestRepository pullRequests,
                                PrEventRepository events,
                                PrRiskScoreRepository scores,
                                RepoRuleRepository rules,
                                OrgRepository orgs,
                                OrgSettingsRepository orgSettings,
                                FeatureBuilder featureBuilder,
                                MlClient mlClient,
                                RuleEvaluator ruleEvaluator,
                                RealtimeGateway realtime,
                                SlackNotifier slack,
                                ObjectMapper objectMapper) {
        this.repos = repos;
        this.pullRequests = pullRequests;
        this.events = events;
        this.scores = scores;
        this.rules = rules;
        this.orgs = orgs;
        this.orgSettings = orgSettings;
        this.featureBuilder = featureBuilder;
        this.mlClient = mlClient;
        this.ruleEvaluator = ruleEvaluator;
        this.realtime = realtime;
        this.slack = slack;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves which org a delivery belongs to.
     *
     * <p>The repo's own org when it is already connected, otherwise the oldest org — this is a
     * single-tenant deployment, so a delivery for a not-yet-connected repo is adopted rather than
     * dropped. (The previous {@code UNION ... LIMIT 1} expressed the same intent but left the
     * choice to Postgres' row order when the repo was unknown; ordering by creation makes it
     * deterministic.)
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveOrgId(String repoFullName) {
        return repos.findFirstByFullName(repoFullName)
                .map(Repo::getOrgId)
                .or(() -> orgs.findFirstByOrderByCreatedAtAsc().map(com.meridian.api.orgs.Org::getId));
    }

    /** Upserts the repo, the PR and the event record in one transaction. */
    @Transactional
    public IngestResult ingestPullRequest(UUID orgId, String event, String action, JsonNode payload) {
        JsonNode repository = payload.path("repository");
        String repoFullName = repository.path("full_name").asString("");

        Repo existingRepo = repos.findByOrgIdAndFullName(orgId, repoFullName)
                .orElseGet(() -> new Repo(orgId, repoFullName,
                        repository.path("default_branch").asString("main")));
        long repoGithubId = repository.path("id").asLong(0L);
        if (repoGithubId != 0L) {
            existingRepo.setGithubId(repoGithubId);
        }
        final Repo repo = repos.save(existingRepo);

        JsonNode p = payload.path("pull_request");
        int number = p.path("number").asInt(0);

        PullRequest pr = pullRequests.findByRepoIdAndNumber(repo.getId(), number)
                .orElseGet(() -> new PullRequest(repo.getId(), number));

        // merged wins over closed — GitHub reports a merged PR as state=closed with merged=true.
        String state = p.path("merged").asBoolean(false) ? "merged" : p.path("state").asString("open");

        long prGithubId = p.path("id").asLong(0L);
        if (prGithubId != 0L) {
            pr.setGithubId(prGithubId);
        }
        pr.setTitle(p.path("title").asString(""));
        pr.setBody(p.path("body").asString(null));
        pr.setAuthorLogin(p.path("user").path("login").asString(""));
        pr.setAuthorAvatar(p.path("user").path("avatar_url").asString(null));
        pr.setState(state);
        pr.setDraft(p.path("draft").asBoolean(false));
        pr.setBaseRef(p.path("base").path("ref").asString(""));
        pr.setHeadRef(p.path("head").path("ref").asString(""));
        pr.setAdditions(p.path("additions").asInt(0));
        pr.setDeletions(p.path("deletions").asInt(0));
        pr.setChangedFiles(p.path("changed_files").asInt(0));
        pr.setCommitsCount(p.path("commits").asInt(0));
        pr.setFilePaths(stringArray(p.path("file_paths")));
        pr.setLabels(objectArrayField(p.path("labels"), "name"));
        pr.setRequestedReviewers(objectArrayField(p.path("requested_reviewers"), "login"));
        pr.setUrl(p.path("html_url").asString(null));
        pr.setOpenedAt(instant(p, "created_at", Instant.now()));
        pr.setUpdatedAt(instant(p, "updated_at", Instant.now()));
        pr.setMergedAt(instant(p, "merged_at", null));
        pr.setClosedAt(instant(p, "closed_at", null));

        pr = pullRequests.save(pr);

        events.save(new PrEvent(
                pr.getId(),
                event + "." + action,
                payload.path("sender").path("login").asString(null),
                payload.toString()));

        return new IngestResult(
                pr.getId(),
                repo.getId(),
                repoFullName,
                pr.getNumber(),
                pr.getTitle(),
                pr.getAuthorLogin(),
                pr.getUrl(),
                pr.getAdditions(),
                pr.getDeletions(),
                pr.getChangedFiles(),
                pr.getCommitsCount(),
                pr.getFilePaths(),
                pr.getLabels(),
                pr.getOpenedAt(),
                FeatureBuilder.commitMessages(p));
    }

    public boolean shouldScore(String action) {
        return SCORING_ACTIONS.contains(action);
    }

    /**
     * Scores a PR and records the result. Runs off the request thread — see the class comment.
     *
     * <p>{@code REQUIRES_NEW} because the ingest transaction has already committed by the time this
     * runs; this needs its own.
     */
    @Async("webhookScoringExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scoreAndPersist(UUID orgId, IngestResult ingested, String diffText) {
        try {
            Map<String, Object> features = featureBuilder.build(
                    ingested.repoId(),
                    ingested.authorLogin(),
                    ingested.filePaths(),
                    ingested.additions(),
                    ingested.deletions(),
                    ingested.changedFiles(),
                    ingested.commitsCount(),
                    ingested.openedAt(),
                    ingested.commitMessages());

            MlScore ml = mlClient.score(features);

            List<RepoRule> repoRules = rules.findByRepoId(ingested.repoId());
            RuleEvaluator.Result ruleResult = ruleEvaluator.evaluate(
                    repoRules,
                    new RuleEvaluator.PrContext(
                            ingested.authorLogin(),
                            ingested.filePaths(),
                            ingested.additions(),
                            ingested.deletions(),
                            ingested.changedFiles(),
                            ingested.labels()),
                    diffText);

            // Rules escalate, never de-escalate.
            Tier finalTier = Tier.max(ruleResult.escalatedTier(), Tier.fromOrLow(ml.tier()));

            scores.save(new PrRiskScore(
                    ingested.prId(),
                    ml.score(),
                    finalTier.wire(),
                    ml.confidence(),
                    objectMapper.writeValueAsString(features),
                    ml.contributions() == null || ml.contributions().json() == null
                            ? "[]" : ml.contributions().json(),
                    objectMapper.writeValueAsString(ruleResult.hits()),
                    ml.modelVersion()));

            realtime.prScored(orgId, ingested.prId(), ml.score(), finalTier.wire(), ingested.repoFullName());

            notifySlackIfConfigured(orgId, ingested, finalTier, ml);

        } catch (Exception ex) {
            // The PR is ingested either way; it will be rescored on the next synchronize.
            log.error("score_failed pr={} reason={}", ingested.prId(), ex.getMessage(), ex);
        }
    }

    private void notifySlackIfConfigured(UUID orgId, IngestResult ingested, Tier tier, MlScore ml) {
        Optional<OrgSettings> settings = orgSettings.findById(orgId);
        if (settings.isEmpty()) {
            return;
        }
        List<String> notifyTiers = settings.get().getNotifyOnTiers();
        if (notifyTiers == null || !notifyTiers.contains(tier.wire())) {
            return;
        }
        slack.notify(
                settings.get().getSlackWebhookUrl(),
                "[%s] %s#%d".formatted(tier.wire().toUpperCase(java.util.Locale.ROOT),
                        ingested.repoFullName(), ingested.number()),
                SlackNotifier.riskPrBlocks(
                        ingested.repoFullName(),
                        ingested.number(),
                        ingested.title(),
                        ingested.authorLogin(),
                        ingested.url(),
                        ingested.additions(),
                        ingested.deletions(),
                        ingested.changedFiles(),
                        tier.wire(),
                        ml.score()));
    }

    /**
     * Records a submitted review and stamps the review timestamps.
     *
     * @return the PR's id when it is one we track, otherwise empty
     */
    @Transactional
    public Optional<UUID> ingestReview(UUID orgId, JsonNode payload) {
        String repoFullName = payload.path("repository").path("full_name").asString("");
        int number = payload.path("pull_request").path("number").asInt(0);

        Optional<UUID> prId = pullRequests.findIdByOrgAndRepoAndNumber(orgId, repoFullName, number);
        if (prId.isEmpty()) {
            return Optional.empty();
        }

        events.save(new PrEvent(
                prId.get(),
                "review.submitted",
                payload.path("review").path("user").path("login").asString(null),
                payload.toString()));

        Instant now = Instant.now();
        pullRequests.stampFirstReview(prId.get(), now);
        if ("approved".equals(payload.path("review").path("state").asString(""))) {
            pullRequests.stampApproved(prId.get(), now);
        }

        return prId;
    }

    // --- payload helpers -----------------------------------------------------

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            out.add(item.asString(""));
        }
        return out;
    }

    /** Pulls one field out of each object in an array — e.g. label names, reviewer logins. */
    private static List<String> objectArrayField(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String value = item.path(field).asString(null);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static Instant instant(JsonNode node, String field, Instant fallback) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** What ingestion produced, carried forward into the (asynchronous) scoring step. */
    public record IngestResult(
            UUID prId,
            UUID repoId,
            String repoFullName,
            int number,
            String title,
            String authorLogin,
            String url,
            int additions,
            int deletions,
            int changedFiles,
            int commitsCount,
            List<String> filePaths,
            List<String> labels,
            Instant openedAt,
            List<String> commitMessages
    ) {}
}
