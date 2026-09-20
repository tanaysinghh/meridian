package com.meridian.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Two serialization rules that keep responses byte-identical to what the Node service emitted.
 *
 * <p>Both exist purely for contract fidelity. The frontend happens to tolerate either form, but the
 * migration's stated goal is that the API contract does not move, so the differences are removed
 * here rather than left for a consumer to absorb. See DECISIONS.md — if the contract is ever
 * revised deliberately, this class is the single place to undo them.
 */
@Configuration
public class JacksonConfig {

    /**
     * {@code JSON.stringify(new Date(...))} always emits exactly three fractional digits and a
     * {@code Z} suffix. Postgres stores {@code TIMESTAMPTZ} at microsecond precision, so Java reads
     * back {@code …054327Z} where node-postgres — which parses into a millisecond-precision JS Date
     * — produced {@code …054Z}.
     */
    private static final DateTimeFormatter JS_ISO_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    public JacksonModule meridianCompatibilityModule() {
        SimpleModule module = new SimpleModule("meridian-compatibility");
        module.addSerializer(Instant.class, new JsDateInstantSerializer());
        module.addSerializer(BigDecimal.class, new NumericAsStringSerializer());
        return module;
    }

    /** Millisecond-precision ISO-8601, matching a JS {@code Date} round-tripped through JSON. */
    static final class JsDateInstantSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(JS_ISO_INSTANT.format(value.truncatedTo(ChronoUnit.MILLIS)));
            }
        }
    }

    /**
     * Postgres {@code NUMERIC} as a JSON string.
     *
     * <p>node-postgres does not parse {@code NUMERIC} into a JS number — doing so would silently
     * lose precision on values outside the double range — so it hands back the column's text and
     * the old API emitted {@code "score": "0.524"} and {@code "risk_threshold": "0.550"}. Every
     * {@link BigDecimal} in this codebase maps to such a column, so the rule is global.
     *
     * <p>{@code toPlainString} preserves the column's scale, which is what makes {@code 0.550} stay
     * {@code "0.550"} rather than becoming {@code "0.55"}.
     */
    static final class NumericAsStringSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toPlainString());
            }
        }
    }
}
