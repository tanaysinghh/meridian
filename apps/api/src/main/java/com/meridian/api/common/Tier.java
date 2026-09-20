package com.meridian.api.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk tiers, in ascending severity.
 *
 * <p>The enum's natural order <em>is</em> the comparison order, which is what the old
 * {@code TIER_ORDER.indexOf(...)} comparisons relied on. Rules may escalate a tier and never
 * de-escalate it, so {@link #max(Tier, Tier)} is the only combinator callers need.
 *
 * <p>Wire form stays lowercase, matching the {@code CHECK (tier IN ('low','medium','high','critical'))}
 * constraint and every tier string the frontend already switches on.
 */
public enum Tier {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    private final String wire;

    Tier(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Tier from(String value) {
        if (value == null) {
            return null;
        }
        for (Tier t : values()) {
            if (t.wire.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown tier: " + value);
    }

    /**
     * Lenient lookup for values read back from storage or an upstream service, where an unknown
     * string should degrade rather than throw. Falls back to {@link #LOW}, the same default the old
     * {@code COALESCE(s.tier, 'low')} used.
     */
    public static Tier fromOrLow(String value) {
        if (value == null) {
            return LOW;
        }
        for (Tier t : values()) {
            if (t.wire.equalsIgnoreCase(value)) {
                return t;
            }
        }
        return LOW;
    }

    public static Tier max(Tier a, Tier b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
