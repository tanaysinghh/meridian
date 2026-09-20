package com.meridian.api.rules;

import com.meridian.api.common.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies per-repo rules and the always-on security checks on top of the model's score.
 *
 * <p>The contract, unchanged from the previous implementation: <b>rules only escalate</b>. The
 * result is the highest tier any matching rule asks for, and the caller then takes
 * {@code max(ruleTier, modelTier)}. Nothing here can lower a tier the model assigned, which is what
 * makes it safe to let teams write their own rules.
 *
 * <p>Two built-in checks run regardless of configuration, because they are the cases where a missed
 * escalation is most costly:
 * <ul>
 *   <li>a diff touching auth, session, permission, secret or credential paths escalates to
 *       {@code high};</li>
 *   <li>anything resembling a committed credential in the diff escalates to {@code critical}.</li>
 * </ul>
 *
 * <p>Predicates are read from the stored JSON rather than from the request DTOs, so a rule written
 * before a validation rule changed still evaluates. An unparseable or unknown predicate does not
 * match and does not throw — one malformed rule must not stop the other rules, or the score itself,
 * from being produced.
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    /** Paths whose modification always warrants a closer look. */
    private static final List<Pattern> SECURITY_PATH_PATTERNS = List.of(
            Pattern.compile("auth", Pattern.CASE_INSENSITIVE),
            Pattern.compile("session", Pattern.CASE_INSENSITIVE),
            Pattern.compile("token", Pattern.CASE_INSENSITIVE),
            Pattern.compile("permission", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\.env", Pattern.CASE_INSENSITIVE),
            Pattern.compile("secret", Pattern.CASE_INSENSITIVE),
            Pattern.compile("credential", Pattern.CASE_INSENSITIVE));

    /** Shapes that look like a leaked credential in a diff. */
    private static final List<SecretPattern> SECRET_PATTERNS = List.of(
            new SecretPattern("aws_access_key", Pattern.compile("AKIA[0-9A-Z]{16}")),
            new SecretPattern("private_key",
                    Pattern.compile("-----BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE KEY-----")),
            new SecretPattern("generic_secret",
                    Pattern.compile("(api[_-]?key|secret|token)['\"\\s:=]+[A-Za-z0-9_\\-]{20,}",
                            Pattern.CASE_INSENSITIVE)));

    private final ObjectMapper objectMapper;

    public RuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param rules    the repo's configured rules, enabled and disabled alike (disabled ones are
     *                 skipped here)
     * @param pr       the PR's evaluated attributes
     * @param diffText the raw diff, when available, for secret scanning
     */
    public Result evaluate(List<RepoRule> rules, PrContext pr, String diffText) {
        List<Hit> hits = new ArrayList<>();
        Tier escalated = Tier.LOW;

        for (RepoRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            try {
                JsonNode predicate = objectMapper.readTree(rule.getPredicate());
                if (!matches(predicate, pr)) {
                    continue;
                }
                JsonNode action = objectMapper.readTree(rule.getAction());
                String reason = action.path("reason").asString("");
                Tier target = Tier.fromOrLow(action.path("escalate_to").asString("low"));

                hits.add(new Hit(rule.getName(), reason, target.wire(), "rule"));
                escalated = Tier.max(escalated, target);
            } catch (Exception ex) {
                // A single bad rule must not take down scoring for the PR.
                log.warn("rule_evaluation_failed rule={} reason={}", rule.getName(), ex.getClass().getSimpleName());
            }
        }

        // Built-in: sensitive paths.
        if (pr.filePaths().stream().anyMatch(RuleEvaluator::touchesSecurityPath)) {
            hits.add(new Hit(
                    "security:sensitive-paths",
                    "Diff touches auth/secret/permission code",
                    Tier.HIGH.wire(),
                    "builtin"));
            escalated = Tier.max(escalated, Tier.HIGH);
        }

        // Built-in: possible committed secret.
        if (diffText != null && !diffText.isEmpty()) {
            for (SecretPattern secret : SECRET_PATTERNS) {
                if (secret.pattern().matcher(diffText).find()) {
                    hits.add(new Hit(
                            "security:possible-" + secret.name(),
                            "Possible secret detected in diff",
                            Tier.CRITICAL.wire(),
                            "builtin"));
                    escalated = Tier.CRITICAL;
                }
            }
        }

        return new Result(escalated, hits);
    }

    private static boolean touchesSecurityPath(String path) {
        if (path == null) {
            return false;
        }
        return SECURITY_PATH_PATTERNS.stream().anyMatch(p -> p.matcher(path).find());
    }

    private boolean matches(JsonNode predicate, PrContext pr) {
        String type = predicate.path("type").asString("");
        return switch (type) {
            case "path_glob" -> {
                String glob = predicate.path("glob").asString("");
                yield pr.filePaths().stream().anyMatch(p -> p != null && globMatches(glob, p));
            }
            case "touches_paths" -> {
                List<String> needles = strings(predicate.path("paths"));
                yield pr.filePaths().stream()
                        .anyMatch(p -> p != null && needles.stream().anyMatch(p::contains));
            }
            case "author_in" -> strings(predicate.path("authors")).contains(pr.authorLogin());

            // Accepts `lines` (what the API validates and the UI sends) and falls back to `value`
            // for any rule written directly against the old evaluator's field name.
            case "size_gt" -> {
                long threshold = predicate.has("lines")
                        ? predicate.path("lines").asLong(Long.MAX_VALUE)
                        : predicate.path("value").asLong(Long.MAX_VALUE);
                yield (long) pr.additions() + pr.deletions() > threshold;
            }
            case "files_gt" -> {
                long threshold = predicate.has("files")
                        ? predicate.path("files").asLong(Long.MAX_VALUE)
                        : predicate.path("value").asLong(Long.MAX_VALUE);
                yield pr.changedFiles() > threshold;
            }

            // Matches file paths, not the `target` field — preserved from the previous evaluator.
            case "regex_match" -> {
                try {
                    Pattern pattern = Pattern.compile(predicate.path("pattern").asString(""));
                    yield pr.filePaths().stream().anyMatch(p -> p != null && pattern.matcher(p).find());
                } catch (PatternSyntaxException ex) {
                    log.warn("rule_regex_invalid pattern_rejected");
                    yield false;
                }
            }
            case "label_in" -> {
                List<String> wanted = strings(predicate.path("labels"));
                yield pr.labels().stream().anyMatch(wanted::contains);
            }
            default -> false;
        };
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            out.add(item.asString(""));
        }
        return out;
    }

    /**
     * The minimal glob dialect the previous implementation supported: {@code **} spans separators,
     * a single {@code *} does not, everything else is literal. Anchored at both ends.
     */
    static boolean globMatches(String glob, String path) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if ("\\.+^$(){}|[]".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else if (c == '?') {
                // Not a wildcard in the original dialect — kept literal.
                regex.append("\\?");
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        try {
            return Pattern.compile(regex.toString()).matcher(path).matches();
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }

    /** The PR attributes a predicate can test. */
    public record PrContext(
            String authorLogin,
            List<String> filePaths,
            int additions,
            int deletions,
            int changedFiles,
            List<String> labels
    ) {}

    /** One rule that fired, as recorded in {@code pr_risk_scores.rule_hits}. */
    public record Hit(String rule, String reason, String tier, String source) {}

    public record Result(Tier escalatedTier, List<Hit> hits) {}

    private record SecretPattern(String name, Pattern pattern) {}
}
