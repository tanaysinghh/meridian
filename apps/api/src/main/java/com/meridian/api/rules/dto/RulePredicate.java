package com.meridian.api.rules.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The condition half of a rule — a discriminated union keyed on {@code type}.
 *
 * <p>Jackson's polymorphic deserialization gives the same guarantee the Zod
 * {@code discriminatedUnion('type', ...)} did: an unrecognised {@code type} fails to bind and comes
 * back as a 400, and each arm's own constraints are checked. That strictness is the point — the
 * evaluator should never meet a shape it did not expect, because a rule that fails to parse at
 * evaluation time would silently stop escalating.
 *
 * <p>The bounds on each arm (path lengths, list sizes, line counts) are carried over unchanged. They
 * exist to keep a stored predicate from becoming a denial-of-service against the scoring path.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = RulePredicate.PathGlob.class, name = "path_glob"),
        @JsonSubTypes.Type(value = RulePredicate.TouchesPaths.class, name = "touches_paths"),
        @JsonSubTypes.Type(value = RulePredicate.AuthorIn.class, name = "author_in"),
        @JsonSubTypes.Type(value = RulePredicate.SizeGt.class, name = "size_gt"),
        @JsonSubTypes.Type(value = RulePredicate.RegexMatch.class, name = "regex_match")
})
public sealed interface RulePredicate {

    String type();

    /** Any file path matching this glob. Supports {@code **} and a single-segment {@code *}. */
    record PathGlob(
            @JsonProperty("type") String type,
            @JsonProperty("glob")
            @NotBlank(message = "Required")
            @Size(min = 1, max = 500, message = "String must contain at most 500 character(s)")
            String glob
    ) implements RulePredicate {}

    /** Any file path containing one of these substrings. */
    record TouchesPaths(
            @JsonProperty("type") String type,
            @JsonProperty("paths")
            @NotEmpty(message = "Array must contain at least 1 element(s)")
            @Size(max = 50, message = "Array must contain at most 50 element(s)")
            List<@NotBlank @Size(max = 500) String> paths
    ) implements RulePredicate {}

    /** The PR's author is one of these logins. */
    record AuthorIn(
            @JsonProperty("type") String type,
            @JsonProperty("authors")
            @NotEmpty(message = "Array must contain at least 1 element(s)")
            @Size(max = 200, message = "Array must contain at most 200 element(s)")
            List<@NotBlank @Size(max = 200) String> authors
    ) implements RulePredicate {}

    /**
     * Total changed lines (additions + deletions) exceeds this.
     *
     * <p>The field is {@code lines}. The previous evaluator read {@code pred.value} instead, so a
     * rule created through the API could never match — see DECISIONS.md.
     */
    record SizeGt(
            @JsonProperty("type") String type,
            @JsonProperty("lines")
            @NotNull(message = "Required")
            @Positive(message = "Number must be greater than 0")
            @Max(value = 1_000_000, message = "Number must be less than or equal to 1000000")
            Integer lines
    ) implements RulePredicate {}

    /**
     * A regular expression match.
     *
     * <p>{@code target} is accepted and stored but the evaluator matches against file paths
     * regardless — preserved as-is rather than changed, because rules relying on the current
     * behaviour are live. See DECISIONS.md.
     */
    record RegexMatch(
            @JsonProperty("type") String type,
            @JsonProperty("pattern")
            @NotBlank(message = "Required")
            @Size(min = 1, max = 500, message = "String must contain at most 500 character(s)")
            String pattern,
            @JsonProperty("target")
            @jakarta.validation.constraints.Pattern(regexp = "title|body|diff", message = "Invalid enum value")
            String target
    ) implements RulePredicate {

        public RegexMatch {
            // Zod applied .default('title') on this field.
            if (target == null || target.isBlank()) {
                target = "title";
            }
        }
    }

    /** Wrapper used where a predicate is nested and needs cascading validation. */
    record Holder(@Valid @NotNull RulePredicate predicate) {}
}
