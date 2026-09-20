package com.meridian.api.rules;

import com.meridian.api.common.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules engine decides the tier a PR is finally reported at, so these tests pin the two
 * properties the whole feature rests on: rules can only escalate, and the built-in security checks
 * fire regardless of configuration.
 */
class RuleEvaluatorTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RuleEvaluator evaluator = new RuleEvaluator(mapper);

    private static RepoRule rule(String name, String predicate, String action) {
        return new RepoRule(UUID.randomUUID(), name, true, predicate, action);
    }

    private static RepoRule disabled(String name, String predicate, String action) {
        return new RepoRule(UUID.randomUUID(), name, false, predicate, action);
    }

    private static RuleEvaluator.PrContext pr(List<String> paths) {
        return new RuleEvaluator.PrContext("marcus-c", paths, 100, 20, 5, List.of());
    }

    @Test
    @DisplayName("a matching rule escalates to its configured tier")
    void escalatesOnMatch() {
        var result = evaluator.evaluate(
                List.of(rule("billing",
                        "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                        "{\"escalate_to\":\"high\",\"reason\":\"Touches billing pathway\"}")),
                pr(List.of("src/billing/charge.ts")),
                "");

        assertThat(result.escalatedTier()).isEqualTo(Tier.HIGH);
        assertThat(result.hits()).extracting(RuleEvaluator.Hit::rule).contains("billing");
        assertThat(result.hits()).extracting(RuleEvaluator.Hit::source).contains("rule");
    }

    @Test
    @DisplayName("a non-matching rule leaves the tier alone")
    void noMatchNoEscalation() {
        var result = evaluator.evaluate(
                List.of(rule("billing",
                        "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                        "{\"escalate_to\":\"critical\",\"reason\":\"r\"}")),
                pr(List.of("docs/readme.md")),
                "");

        assertThat(result.escalatedTier()).isEqualTo(Tier.LOW);
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("disabled rules are skipped")
    void disabledRulesAreSkipped() {
        var result = evaluator.evaluate(
                List.of(disabled("billing",
                        "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                        "{\"escalate_to\":\"critical\",\"reason\":\"r\"}")),
                pr(List.of("src/billing/charge.ts")),
                "");

        assertThat(result.escalatedTier()).isEqualTo(Tier.LOW);
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("the highest matching tier wins, and rules never de-escalate")
    void highestTierWins() {
        var result = evaluator.evaluate(
                List.of(
                        rule("a", "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                                "{\"escalate_to\":\"critical\",\"reason\":\"r\"}"),
                        rule("b", "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                                "{\"escalate_to\":\"medium\",\"reason\":\"r\"}")),
                pr(List.of("src/billing/charge.ts")),
                "");

        assertThat(result.escalatedTier()).isEqualTo(Tier.CRITICAL);

        // Tier.max never returns the lower of the two, which is what stops a rule lowering a
        // model-assigned tier once the caller combines them.
        assertThat(Tier.max(Tier.CRITICAL, Tier.LOW)).isEqualTo(Tier.CRITICAL);
        assertThat(Tier.max(Tier.LOW, Tier.HIGH)).isEqualTo(Tier.HIGH);
    }

    @Test
    @DisplayName("touching auth paths escalates to high with no rule configured")
    void builtInSensitivePathEscalation() {
        var result = evaluator.evaluate(List.of(), pr(List.of("src/auth/session.ts")), "");

        assertThat(result.escalatedTier()).isEqualTo(Tier.HIGH);
        assertThat(result.hits())
                .extracting(RuleEvaluator.Hit::rule)
                .contains("security:sensitive-paths");
        assertThat(result.hits()).extracting(RuleEvaluator.Hit::source).contains("builtin");
    }

    @Test
    @DisplayName("an apparent credential in the diff escalates to critical")
    void builtInSecretDetection() {
        var awsKey = evaluator.evaluate(List.of(), pr(List.of("deploy.sh")),
                "+ AWS_KEY = AKIAIOSFODNN7EXAMPLE");
        assertThat(awsKey.escalatedTier()).isEqualTo(Tier.CRITICAL);
        assertThat(awsKey.hits()).extracting(RuleEvaluator.Hit::rule)
                .contains("security:possible-aws_access_key");

        var privateKey = evaluator.evaluate(List.of(), pr(List.of("deploy.sh")),
                "+ -----BEGIN RSA PRIVATE KEY-----");
        assertThat(privateKey.escalatedTier()).isEqualTo(Tier.CRITICAL);

        var clean = evaluator.evaluate(List.of(), pr(List.of("docs/readme.md")), "+ just some prose");
        assertThat(clean.escalatedTier()).isEqualTo(Tier.LOW);
    }

    @Test
    @DisplayName("size_gt compares total changed lines against `lines`")
    void sizeGtUsesLinesField() {
        // 100 additions + 20 deletions = 120 changed lines.
        String action = "{\"escalate_to\":\"high\",\"reason\":\"large\"}";

        var over = evaluator.evaluate(
                List.of(rule("big", "{\"type\":\"size_gt\",\"lines\":100}", action)),
                pr(List.of("docs/readme.md")), "");
        assertThat(over.escalatedTier()).isEqualTo(Tier.HIGH);

        var under = evaluator.evaluate(
                List.of(rule("big", "{\"type\":\"size_gt\",\"lines\":500}", action)),
                pr(List.of("docs/readme.md")), "");
        assertThat(under.escalatedTier()).isEqualTo(Tier.LOW);
    }

    @Test
    @DisplayName("touches_paths matches on substring, author_in on exact login")
    void otherPredicateTypes() {
        String action = "{\"escalate_to\":\"high\",\"reason\":\"r\"}";

        var touches = evaluator.evaluate(
                List.of(rule("t", "{\"type\":\"touches_paths\",\"paths\":[\"src/billing/\"]}", action)),
                pr(List.of("app/src/billing/charge.ts")), "");
        assertThat(touches.escalatedTier()).isEqualTo(Tier.HIGH);

        var author = evaluator.evaluate(
                List.of(rule("a", "{\"type\":\"author_in\",\"authors\":[\"marcus-c\"]}", action)),
                pr(List.of("docs/readme.md")), "");
        assertThat(author.escalatedTier()).isEqualTo(Tier.HIGH);

        var otherAuthor = evaluator.evaluate(
                List.of(rule("a", "{\"type\":\"author_in\",\"authors\":[\"someone-else\"]}", action)),
                pr(List.of("docs/readme.md")), "");
        assertThat(otherAuthor.escalatedTier()).isEqualTo(Tier.LOW);
    }

    @Test
    @DisplayName("a malformed rule is skipped without breaking the others")
    void malformedRuleDoesNotBreakEvaluation() {
        var result = evaluator.evaluate(
                List.of(
                        rule("broken", "not valid json at all", "{\"escalate_to\":\"high\",\"reason\":\"r\"}"),
                        rule("good", "{\"type\":\"path_glob\",\"glob\":\"src/billing/**\"}",
                                "{\"escalate_to\":\"high\",\"reason\":\"r\"}")),
                pr(List.of("src/billing/charge.ts")),
                "");

        // Scoring must still produce a result — one bad rule cannot take down the pipeline.
        assertThat(result.escalatedTier()).isEqualTo(Tier.HIGH);
        assertThat(result.hits()).extracting(RuleEvaluator.Hit::rule).contains("good");
    }

    @Test
    @DisplayName("glob dialect: ** spans separators, * does not")
    void globSemantics() {
        assertThat(RuleEvaluator.globMatches("src/billing/**", "src/billing/a/b/c.ts")).isTrue();
        assertThat(RuleEvaluator.globMatches("src/*.ts", "src/index.ts")).isTrue();
        assertThat(RuleEvaluator.globMatches("src/*.ts", "src/nested/index.ts")).isFalse();
        assertThat(RuleEvaluator.globMatches("src/billing/**", "src/auth/a.ts")).isFalse();
        // Anchored at both ends.
        assertThat(RuleEvaluator.globMatches("src/auth", "src/auth/session.ts")).isFalse();
    }
}
