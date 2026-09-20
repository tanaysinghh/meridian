package com.meridian.api.digest;

import com.meridian.api.common.Tuples;
import com.meridian.api.integrations.EmailSender;
import com.meridian.api.orgs.OrgSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds and sends the weekly review digest.
 *
 * <p>A straight port, including the HTML template. The content is unchanged: tier mix for the week,
 * the open high-risk PRs sorted worst-first, the revert rate, and who is carrying the review load.
 *
 * <p>Nothing here schedules itself — the previous implementation was the same, exposing
 * {@code sendWeeklyDigest} for an external trigger to call. Wiring it to a scheduler is deliberately
 * left out of this migration.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private static final String TIERS_SQL = """
            SELECT s.tier, COUNT(*)::int AS n FROM pull_requests p
              JOIN repos r ON r.id = p.repo_id
              JOIN LATERAL (SELECT tier FROM pr_risk_scores WHERE pr_id=p.id ORDER BY scored_at DESC LIMIT 1) s ON true
             WHERE r.org_id = :orgId AND p.opened_at > now() - interval '7 days'
             GROUP BY s.tier
            """;

    private static final String TOP_RISKY_SQL = """
            SELECT p.number, p.title, r.full_name AS repo, s.tier, s.score
              FROM pull_requests p JOIN repos r ON r.id=p.repo_id
              JOIN LATERAL (SELECT tier, score FROM pr_risk_scores WHERE pr_id=p.id ORDER BY scored_at DESC LIMIT 1) s ON true
             WHERE r.org_id = :orgId AND p.state='open' AND s.tier IN ('high','critical')
             ORDER BY s.score DESC LIMIT 10
            """;

    private static final String REVERTS_SQL = """
            SELECT COUNT(*) FILTER (WHERE o.reverted)::int AS reverts,
                   COUNT(*)::int AS merges
              FROM pull_requests p JOIN repos r ON r.id=p.repo_id
              LEFT JOIN pr_outcomes o ON o.pr_id=p.id
             WHERE r.org_id = :orgId AND p.merged_at > now() - interval '7 days'
            """;

    private static final String LOAD_SQL = """
            SELECT login, COUNT(*)::int AS open_reviews FROM (
              SELECT unnest(p.requested_reviewers) AS login
                FROM pull_requests p JOIN repos r ON r.id=p.repo_id
               WHERE r.org_id = :orgId AND p.state='open'
            ) t GROUP BY login ORDER BY open_reviews DESC LIMIT 5
            """;

    @PersistenceContext
    private EntityManager em;

    private final OrgSettingsRepository orgSettings;
    private final EmailSender emailSender;

    public DigestService(OrgSettingsRepository orgSettings, EmailSender emailSender) {
        this.orgSettings = orgSettings;
        this.emailSender = emailSender;
    }

    public record Digest(String subject, String html) {
    }

    @Transactional(readOnly = true)
    public Digest build(UUID orgId) {
        List<Tuple> tiers = rows(TIERS_SQL, orgId);
        List<Tuple> topRisky = rows(TOP_RISKY_SQL, orgId);
        List<Tuple> reverts = rows(REVERTS_SQL, orgId);
        List<Tuple> load = rows(LOAD_SQL, orgId);

        return new Digest("Meridian — weekly review digest", renderHtml(tiers, topRisky, reverts, load));
    }

    /** Sends the digest to every configured recipient. A no-op when none are configured. */
    @Transactional(readOnly = true)
    public void sendWeeklyDigest(UUID orgId) {
        List<String> recipients = orgSettings.findById(orgId)
                .map(s -> s.getDigestRecipients())
                .orElse(List.of());

        if (recipients.isEmpty()) {
            log.info("digest_skipped_no_recipients orgId={}", orgId);
            return;
        }

        Digest digest = build(orgId);
        for (String to : recipients) {
            emailSender.send(to, digest.subject(), digest.html());
        }
    }

    private String renderHtml(List<Tuple> tiers, List<Tuple> topRisky, List<Tuple> reverts, List<Tuple> load) {
        StringBuilder tierRow = new StringBuilder();
        for (Tuple t : tiers) {
            tierRow.append("<td style=\"padding:6px 12px\"><b>")
                    .append(escape(Tuples.string(t, "tier")))
                    .append("</b>: ")
                    .append(Tuples.intOrZero(t, "n"))
                    .append("</td>");
        }

        StringBuilder riskyList = new StringBuilder();
        for (Tuple t : topRisky) {
            riskyList.append("<li><code>")
                    .append(escape(Tuples.string(t, "repo")))
                    .append("</code> #")
                    .append(Tuples.intOrZero(t, "number"))
                    .append(" — ")
                    .append(escape(Tuples.string(t, "title")))
                    .append(" <span style=\"color:#c00\">[")
                    .append(escape(Tuples.string(t, "tier")))
                    .append(' ')
                    .append(Tuples.decimal(t, "score"))
                    .append("]</span></li>");
        }

        StringBuilder loadList = new StringBuilder();
        for (Tuple t : load) {
            loadList.append("<li>")
                    .append(escape(Tuples.string(t, "login")))
                    .append(": ")
                    .append(Tuples.intOrZero(t, "open_reviews"))
                    .append(" open</li>");
        }

        int revertCount = reverts.isEmpty() ? 0 : Tuples.intOrZero(reverts.get(0), "reverts");
        int mergeCount = reverts.isEmpty() ? 0 : Tuples.intOrZero(reverts.get(0), "merges");
        String revertPct = mergeCount > 0
                ? String.format(java.util.Locale.ROOT, "%.1f", (revertCount * 100.0) / mergeCount)
                : "0.0";

        return """
                <!doctype html><html><body style="font-family:ui-sans-serif,system-ui;background:#0b0d10;color:#e6e8eb;padding:24px">
                    <h1 style="font-family:'Instrument Serif',Georgia,serif;font-weight:400;font-size:28px;margin:0 0 8px">This week in review</h1>
                    <div style="color:#8b93a0;margin-bottom:24px">Generated by Meridian</div>
                    <table><tr>%s</tr></table>
                    <h2 style="margin-top:24px">Top open high-risk PRs</h2>
                    <ul>%s</ul>
                    <h2>Revert rate</h2>
                    <p>%d reverts of %d merges (%s%%)</p>
                    <h2>Reviewer load</h2>
                    <ul>%s</ul>
                  </body></html>
                """.formatted(
                tierRow,
                riskyList.isEmpty() ? "<li>none</li>" : riskyList.toString(),
                revertCount,
                mergeCount,
                revertPct,
                loadList);
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> rows(String sql, UUID orgId) {
        return em.createNativeQuery(sql, Tuple.class).setParameter("orgId", orgId).getResultList();
    }

    /** PR titles are attacker-influenced text landing in an HTML email. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
