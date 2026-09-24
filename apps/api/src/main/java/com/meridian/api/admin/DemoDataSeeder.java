package com.meridian.api.admin;

import com.meridian.api.orgs.Org;
import com.meridian.api.users.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads the sample Acme dataset: four repos, eight pull requests across the risk tiers, escalation
 * rules, an incident linked to the PR that caused it, and the file hotness / ownership tables that
 * reviewer suggestions read.
 *
 * <p>A port of the {@code --demo} half of the old {@code db:seed} script, which was not carried
 * over during the Spring Boot migration. It exists so the dashboard can be demonstrated against
 * realistic data rather than empty states.
 *
 * <p>Attaches everything to the org the bootstrap created, so the admin account and the demo data
 * are always in the same tenant — otherwise the admin would log in and see nothing.
 *
 * <p>Writes are idempotent: existing demo rows for the org are cleared first, so re-running
 * refreshes the timestamps rather than duplicating.
 *
 * <p>Scores are synthesised with the same shape the real pipeline produces — a NUMERIC score, a
 * tier derived from the same thresholds, a feature vector and contributions — so every panel in the
 * UI, including the explanation view, has something to render. They are labelled
 * {@code model_version = 'seed-0.1.0'} so nobody mistakes them for genuine model output.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    @PersistenceContext
    private EntityManager em;

    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    private record DemoRepo(String fullName, String defaultBranch, String riskThreshold) {}

    private record DemoUser(String email, String name, String login, Role role) {}

    private record DemoPr(String repo, int number, String title, String author, String state,
                          int additions, int deletions, int changedFiles, int commits,
                          List<String> files, List<String> labels, List<String> reviewers,
                          int hoursAgo, Integer mergedHoursAgo) {}

    private static final List<DemoRepo> REPOS = List.of(
            new DemoRepo("acme/platform-api", "main", "0.620"),
            new DemoRepo("acme/billing-service", "main", "0.550"),
            new DemoRepo("acme/web-app", "main", "0.650"),
            new DemoRepo("acme/infra", "main", "0.500"));

    /**
     * Demo teammates. They exist so the Reviewers tab has rows and so reviewer suggestions can
     * resolve a GitHub login to a person. None of them can sign in — no password hash is set.
     */
    private static final List<DemoUser> USERS = List.of(
            new DemoUser("lead@example.test", "Priya Rao", "priya-r", Role.TEAM_LEAD),
            new DemoUser("dev1@example.test", "Marcus Chen", "marcus-c", Role.DEVELOPER),
            new DemoUser("dev2@example.test", "Sofia Alvarez", "sofia-a", Role.DEVELOPER),
            new DemoUser("dev3@example.test", "Kenji Watanabe", "kenji-w", Role.DEVELOPER));

    private static final List<DemoPr> PRS = List.of(
            new DemoPr("acme/platform-api", 412,
                    "Refactor authentication middleware to use async JWT verification",
                    "marcus-c", "open", 340, 180, 14, 6,
                    List.of("src/auth/middleware.ts", "src/auth/jwt.ts", "src/auth/session.ts",
                            "src/routes/login.ts", "tests/auth.spec.ts"),
                    List.of("refactor", "auth"), List.of("priya-r", "sofia-a"), 6, null),
            new DemoPr("acme/billing-service", 88,
                    "Fix double-charge on retry when Stripe returns 409",
                    "sofia-a", "open", 42, 12, 3, 2,
                    List.of("src/billing/charge.ts", "src/billing/retry.ts", "tests/charge.spec.ts"),
                    List.of("bug", "billing"), List.of("priya-r"), 2, null),
            new DemoPr("acme/web-app", 1204,
                    "Add dashboard skeleton for reviewer load view",
                    "kenji-w", "open", 620, 8, 22, 11,
                    List.of("src/pages/reviewers.tsx", "src/components/LoadChart.tsx",
                            "src/components/ReviewerRow.tsx"),
                    List.of("feature"), List.of("marcus-c"), 22, null),
            new DemoPr("acme/infra", 57,
                    "Rotate production database credentials",
                    "priya-r", "open", 24, 18, 4, 1,
                    List.of("terraform/secrets.tf", "terraform/rds.tf", "ops/rotate.sh",
                            ".github/workflows/deploy.yml"),
                    List.of("security", "infra"), List.of("marcus-c"), 1, null),
            new DemoPr("acme/platform-api", 410, "chore: bump deps",
                    "kenji-w", "merged", 2200, 2100, 58, 1,
                    List.of("package.json", "package-lock.json"),
                    List.of("dependencies"), List.of("priya-r"), 48, 40),
            new DemoPr("acme/billing-service", 84, "Improve invoice PDF rendering",
                    "marcus-c", "merged", 90, 30, 5, 3,
                    List.of("src/billing/pdf.ts", "src/billing/template.hbs", "tests/pdf.spec.ts"),
                    List.of("enhancement"), List.of("sofia-a"), 96, 90),
            new DemoPr("acme/web-app", 1198, "Update marketing copy on pricing page",
                    "sofia-a", "merged", 22, 20, 1, 1,
                    List.of("src/pages/pricing.tsx"),
                    List.of("docs"), List.of("kenji-w"), 30, 28),
            new DemoPr("acme/platform-api", 405, "Add rate limiting to /login endpoint",
                    "priya-r", "merged", 78, 4, 6, 2,
                    List.of("src/routes/login.ts", "src/middleware/ratelimit.ts",
                            "tests/ratelimit.spec.ts"),
                    List.of("security"), List.of("marcus-c", "sofia-a"), 120, 110));

    private record DemoRule(String repo, String name, String predicate, String action) {}

    private static final List<DemoRule> RULES = List.of(
            new DemoRule("acme/platform-api", "Auth files always high risk",
                    "{\"type\":\"touches_paths\",\"paths\":[\"src/auth/\",\"src/middleware/auth\"]}",
                    "{\"escalate_to\":\"high\",\"reason\":\"Touches authentication code\"}"),
            new DemoRule("acme/billing-service", "Billing code always high risk",
                    "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                    "{\"escalate_to\":\"high\",\"reason\":\"Touches billing pathway\"}"),
            new DemoRule("acme/infra", "Terraform + secrets escalates to critical",
                    "{\"type\":\"touches_paths\",\"paths\":[\"terraform/\",\"secrets\",\".env\"]}",
                    "{\"escalate_to\":\"critical\",\"reason\":\"Infra + secrets change\"}"));

    /** repo, path prefix, change count, revert count. */
    private static final List<Object[]> HOTNESS = List.of(
            new Object[]{"acme/platform-api", "src/auth/", 22, 3},
            new Object[]{"acme/billing-service", "src/billing/", 41, 2},
            new Object[]{"acme/infra", "terraform/", 15, 1},
            new Object[]{"acme/web-app", "src/pages/", 60, 0});

    /** repo, path prefix, owner login, commit count. */
    private static final List<Object[]> OWNERSHIP = List.of(
            new Object[]{"acme/platform-api", "src/auth/", "priya-r", 34},
            new Object[]{"acme/platform-api", "src/auth/", "marcus-c", 12},
            new Object[]{"acme/billing-service", "src/billing/", "sofia-a", 40},
            new Object[]{"acme/billing-service", "src/billing/", "marcus-c", 8},
            new Object[]{"acme/web-app", "src/pages/", "kenji-w", 55},
            new Object[]{"acme/infra", "terraform/", "priya-r", 20});

    @Transactional
    public void seed(Org org) {
        UUID orgId = org.getId();
        log.info("[seed:demo] loading sample dataset into org {}", org.getSlug());

        clearExisting(orgId);

        Map<String, UUID> repoIds = seedRepos(orgId);
        seedUsers(orgId);
        seedRules(repoIds);
        seedPullRequests(repoIds, orgId);
        seedHotnessAndOwnership(repoIds);

        log.info("[seed:demo] done — {} repos, {} pull requests, {} rules",
                REPOS.size(), PRS.size(), RULES.size());
    }

    /** Removes anything a previous demo run left behind, so re-seeding refreshes rather than duplicates. */
    private void clearExisting(UUID orgId) {
        em.createNativeQuery("""
                DELETE FROM pr_risk_scores WHERE pr_id IN (
                  SELECT p.id FROM pull_requests p JOIN repos r ON r.id = p.repo_id WHERE r.org_id = :orgId)
                """).setParameter("orgId", orgId).executeUpdate();
        em.createNativeQuery("""
                DELETE FROM pr_events WHERE pr_id IN (
                  SELECT p.id FROM pull_requests p JOIN repos r ON r.id = p.repo_id WHERE r.org_id = :orgId)
                """).setParameter("orgId", orgId).executeUpdate();
        em.createNativeQuery("DELETE FROM incidents WHERE org_id = :orgId")
                .setParameter("orgId", orgId).executeUpdate();
        em.createNativeQuery("""
                DELETE FROM pr_outcomes WHERE pr_id IN (
                  SELECT p.id FROM pull_requests p JOIN repos r ON r.id = p.repo_id WHERE r.org_id = :orgId)
                """).setParameter("orgId", orgId).executeUpdate();
        em.createNativeQuery("""
                DELETE FROM pull_requests WHERE repo_id IN (SELECT id FROM repos WHERE org_id = :orgId)
                """).setParameter("orgId", orgId).executeUpdate();
        em.createNativeQuery("""
                DELETE FROM repo_rules WHERE repo_id IN (SELECT id FROM repos WHERE org_id = :orgId)
                """).setParameter("orgId", orgId).executeUpdate();
    }

    private Map<String, UUID> seedRepos(UUID orgId) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (DemoRepo r : REPOS) {
            em.createNativeQuery("""
                    INSERT INTO repos (org_id, full_name, default_branch, risk_threshold)
                    VALUES (:orgId, :fullName, :branch, CAST(:threshold AS NUMERIC))
                    ON CONFLICT (org_id, full_name)
                      DO UPDATE SET risk_threshold = EXCLUDED.risk_threshold
                    """)
                    .setParameter("orgId", orgId)
                    .setParameter("fullName", r.fullName())
                    .setParameter("branch", r.defaultBranch())
                    .setParameter("threshold", r.riskThreshold())
                    .executeUpdate();

            Object id = em.createNativeQuery(
                            "SELECT id FROM repos WHERE org_id = :orgId AND full_name = :fullName")
                    .setParameter("orgId", orgId)
                    .setParameter("fullName", r.fullName())
                    .getSingleResult();
            ids.put(r.fullName(), toUuid(id));
        }
        return ids;
    }

    /**
     * Demo teammates get no password hash, so none of them is a usable login — only the
     * bootstrapped admin can sign in. That is deliberate: seeded accounts with known credentials
     * are a liability, and the old script's randomly generated passwords served no purpose here.
     */
    private void seedUsers(UUID orgId) {
        for (DemoUser u : USERS) {
            em.createNativeQuery("""
                    INSERT INTO users (org_id, email, name, github_login, role, avatar_url)
                    VALUES (:orgId, :email, :name, :login, :role, :avatar)
                    ON CONFLICT (email) DO UPDATE SET
                      name = EXCLUDED.name, role = EXCLUDED.role, github_login = EXCLUDED.github_login
                    """)
                    .setParameter("orgId", orgId)
                    .setParameter("email", u.email())
                    .setParameter("name", u.name())
                    .setParameter("login", u.login())
                    .setParameter("role", u.role().wire())
                    .setParameter("avatar", "https://api.dicebear.com/9.x/identicon/svg?seed=" + u.login())
                    .executeUpdate();
        }
    }

    private void seedRules(Map<String, UUID> repoIds) {
        for (DemoRule rule : RULES) {
            em.createNativeQuery("""
                    INSERT INTO repo_rules (repo_id, name, predicate, action)
                    VALUES (:repoId, :name, CAST(:predicate AS jsonb), CAST(:action AS jsonb))
                    """)
                    .setParameter("repoId", repoIds.get(rule.repo()))
                    .setParameter("name", rule.name())
                    .setParameter("predicate", rule.predicate())
                    .setParameter("action", rule.action())
                    .executeUpdate();
        }
    }

    private void seedPullRequests(Map<String, UUID> repoIds, UUID orgId) {
        Instant now = Instant.now();

        for (DemoPr pr : PRS) {
            Instant opened = now.minus(pr.hoursAgo(), ChronoUnit.HOURS);
            Instant merged = pr.mergedHoursAgo() == null
                    ? null
                    : now.minus(pr.mergedHoursAgo(), ChronoUnit.HOURS);

            em.createNativeQuery("""
                    INSERT INTO pull_requests
                      (repo_id, number, title, author_login, author_avatar, state, base_ref, head_ref,
                       additions, deletions, changed_files, commits_count, file_paths, labels,
                       requested_reviewers, url, opened_at, updated_at, merged_at, closed_at)
                    VALUES
                      (:repoId, :number, :title, :author, :avatar, :state, 'main', :head,
                       :additions, :deletions, :changedFiles, :commits, :files, :labels,
                       :reviewers, :url, :openedAt, :updatedAt, :mergedAt, :closedAt)
                    """)
                    .setParameter("repoId", repoIds.get(pr.repo()))
                    .setParameter("number", pr.number())
                    .setParameter("title", pr.title())
                    .setParameter("author", pr.author())
                    .setParameter("avatar", "https://api.dicebear.com/9.x/identicon/svg?seed=" + pr.author())
                    .setParameter("state", pr.state())
                    .setParameter("head", "feat/" + pr.number())
                    .setParameter("additions", pr.additions())
                    .setParameter("deletions", pr.deletions())
                    .setParameter("changedFiles", pr.changedFiles())
                    .setParameter("commits", pr.commits())
                    .setParameter("files", pr.files().toArray(String[]::new))
                    .setParameter("labels", pr.labels().toArray(String[]::new))
                    .setParameter("reviewers", pr.reviewers().toArray(String[]::new))
                    .setParameter("url", "https://github.com/" + pr.repo() + "/pull/" + pr.number())
                    .setParameter("openedAt", opened)
                    .setParameter("updatedAt", merged != null ? merged : opened)
                    .setParameter("mergedAt", merged)
                    .setParameter("closedAt", merged)
                    .executeUpdate();

            UUID prId = toUuid(em.createNativeQuery(
                            "SELECT id FROM pull_requests WHERE repo_id = :repoId AND number = :number")
                    .setParameter("repoId", repoIds.get(pr.repo()))
                    .setParameter("number", pr.number())
                    .getSingleResult());

            seedScore(prId, pr);

            // One PR carries a revert and the incident it caused, so the Incidents tab and the
            // revert-rate chart both have something real to show.
            if (pr.number() == 410) {
                em.createNativeQuery("""
                        INSERT INTO pr_outcomes (pr_id, reverted, outcome_notes)
                        VALUES (:prId, true, 'Revert PR #411: dep upgrade broke prod checkout')
                        """).setParameter("prId", prId).executeUpdate();

                em.createNativeQuery("""
                        INSERT INTO incidents
                          (org_id, repo_id, related_pr_id, title, severity, description, occurred_at, resolved_at)
                        VALUES
                          (:orgId, :repoId, :prId, 'Checkout outage after dep bump', 'sev2',
                           'Checkout 500s after dep upgrade',
                           now() - interval '38 hours', now() - interval '35 hours')
                        """)
                        .setParameter("orgId", orgId)
                        .setParameter("repoId", repoIds.get(pr.repo()))
                        .setParameter("prId", prId)
                        .executeUpdate();
            }
        }
    }

    /** Mirrors the shape the real scoring pipeline writes, so the UI has a full explanation to render. */
    private void seedScore(UUID prId, DemoPr pr) {
        BigDecimal score = synthesiseScore(pr);
        String tier = tierFor(score.doubleValue());

        int touchesAuth = matches(pr.files(), "auth") ? 1 : 0;
        int touchesBilling = matches(pr.files(), "billing") ? 1 : 0;
        int touchesInfra = matches(pr.files(), "terraform") || matches(pr.files(), "infra") ? 1 : 0;
        int touchesSecret = matches(pr.files(), "secret") || matches(pr.files(), ".env") ? 1 : 0;
        double commitQuality = pr.title().length() > 30 ? 0.8 : 0.4;

        String features = """
                {"additions":%d,"deletions":%d,"changed_files":%d,"commits_count":%d,\
                "touches_auth":%d,"touches_billing":%d,"touches_infra":%d,"touches_secret":%d,\
                "commit_msg_quality":%s,"author_pr_count":20}"""
                .formatted(pr.additions(), pr.deletions(), pr.changedFiles(), pr.commits(),
                        touchesAuth, touchesBilling, touchesInfra, touchesSecret, commitQuality);

        StringBuilder contributions = new StringBuilder("[");
        contributions.append("{\"feature\":\"changed_files\",\"weight\":")
                .append(round3(Math.min(pr.changedFiles() / 40.0, 0.2))).append(",\"direction\":\"up\"}");
        contributions.append(",{\"feature\":\"additions\",\"weight\":")
                .append(round3(Math.min(pr.additions() / 1000.0, 0.35))).append(",\"direction\":\"up\"}");
        if (touchesAuth == 1) {
            contributions.append(",{\"feature\":\"touches_auth\",\"weight\":0.18,\"direction\":\"up\"}");
        }
        if (touchesBilling == 1) {
            contributions.append(",{\"feature\":\"touches_billing\",\"weight\":0.16,\"direction\":\"up\"}");
        }
        if (touchesInfra == 1) {
            contributions.append(",{\"feature\":\"touches_infra\",\"weight\":0.20,\"direction\":\"up\"}");
        }
        if (touchesSecret == 1) {
            contributions.append(",{\"feature\":\"touches_secret\",\"weight\":0.15,\"direction\":\"up\"}");
        }
        contributions.append(",{\"feature\":\"commit_msg_quality\",\"weight\":0.04,\"direction\":\"")
                .append(commitQuality > 0.6 ? "down" : "up").append("\"}");
        contributions.append(']');

        em.createNativeQuery("""
                INSERT INTO pr_risk_scores
                  (pr_id, score, tier, confidence, features, contributions, rule_hits, model_version)
                VALUES
                  (:prId, CAST(:score AS NUMERIC), :tier, 'high',
                   CAST(:features AS jsonb), CAST(:contributions AS jsonb), '[]'::jsonb, 'seed-0.1.0')
                """)
                .setParameter("prId", prId)
                .setParameter("score", score.toPlainString())
                .setParameter("tier", tier)
                .setParameter("features", features)
                .setParameter("contributions", contributions.toString())
                .executeUpdate();
    }

    private void seedHotnessAndOwnership(Map<String, UUID> repoIds) {
        for (Object[] h : HOTNESS) {
            int changes = (int) h[2];
            int reverts = (int) h[3];
            double score = Math.min(0.99, changes / 60.0 + reverts * 0.1);
            em.createNativeQuery("""
                    INSERT INTO file_hotness (repo_id, path, change_count, revert_count, score)
                    VALUES (:repoId, :path, :changes, :reverts, CAST(:score AS NUMERIC))
                    ON CONFLICT (repo_id, path) DO UPDATE SET
                      change_count = EXCLUDED.change_count,
                      revert_count = EXCLUDED.revert_count,
                      score = EXCLUDED.score
                    """)
                    .setParameter("repoId", repoIds.get((String) h[0]))
                    .setParameter("path", (String) h[1])
                    .setParameter("changes", changes)
                    .setParameter("reverts", reverts)
                    .setParameter("score", round3(score))
                    .executeUpdate();
        }

        for (Object[] o : OWNERSHIP) {
            em.createNativeQuery("""
                    INSERT INTO file_ownership (repo_id, path_prefix, owner_login, commits)
                    VALUES (:repoId, :prefix, :login, :commits)
                    ON CONFLICT (repo_id, path_prefix, owner_login)
                      DO UPDATE SET commits = EXCLUDED.commits
                    """)
                    .setParameter("repoId", repoIds.get((String) o[0]))
                    .setParameter("prefix", (String) o[1])
                    .setParameter("login", (String) o[2])
                    .setParameter("commits", (int) o[3])
                    .executeUpdate();
        }
    }

    /** The old script's synthetic score, kept so the demo spreads across all four tiers. */
    static BigDecimal synthesiseScore(DemoPr pr) {
        double s = 0.15;
        s += Math.min(pr.additions() / 1000.0, 0.35);
        s += Math.min(pr.changedFiles() / 40.0, 0.2);
        if (pr.files().stream().anyMatch(f -> f.matches("(?i).*(auth|secret|billing|terraform|\\.env).*"))) {
            s += 0.25;
        }
        if (pr.title().toLowerCase().startsWith("chore")) {
            s -= 0.05;
        }
        s = Math.max(0.05, Math.min(0.98, s));
        return BigDecimal.valueOf(s).setScale(3, RoundingMode.HALF_UP);
    }

    static String tierFor(double score) {
        if (score >= 0.85) return "critical";
        if (score >= 0.65) return "high";
        if (score >= 0.35) return "medium";
        return "low";
    }

    private static boolean matches(List<String> files, String needle) {
        return files.stream().anyMatch(f -> f.toLowerCase().contains(needle.toLowerCase()));
    }

    private static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
