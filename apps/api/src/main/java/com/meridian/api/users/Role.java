package com.meridian.api.users;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Access levels, lowest to highest.
 *
 * <p>Wire and storage form is the lowercase snake string the {@code CHECK (role IN (...))}
 * constraint already enforces, so the values the frontend and database exchange are unchanged.
 * Spring Security sees these as the authorities {@code ROLE_DEVELOPER}, {@code ROLE_TEAM_LEAD} and
 * {@code ROLE_ADMIN}.
 */
public enum Role {
    DEVELOPER("developer"),
    TEAM_LEAD("team_lead"),
    ADMIN("admin");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The Spring Security authority name for this role. */
    public String authority() {
        return "ROLE_" + name();
    }

    @JsonCreator
    public static Role from(String value) {
        if (value == null) {
            return null;
        }
        for (Role r : values()) {
            if (r.wire.equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown role: " + value);
    }
}
