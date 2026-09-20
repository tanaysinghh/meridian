package com.meridian.api.pullrequests.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /prs/{id}/outcome} — a human recording what happened after a merge.
 *
 * <p>All four fields are optional, defaulting to false / null exactly as the Zod schema's
 * {@code .optional().default(...)} did, so a caller can flip one flag without restating the rest.
 */
public record OutcomeRequest(
        @JsonProperty("reverted") Boolean reverted,
        @JsonProperty("hotfixed") Boolean hotfixed,
        @JsonProperty("caused_incident") Boolean causedIncident,
        @JsonProperty("notes") @Size(max = 4000, message = "String must contain at most 4000 character(s)") String notes
) {
    public boolean revertedOrFalse() {
        return Boolean.TRUE.equals(reverted);
    }

    public boolean hotfixedOrFalse() {
        return Boolean.TRUE.equals(hotfixed);
    }

    public boolean causedIncidentOrFalse() {
        return Boolean.TRUE.equals(causedIncident);
    }
}
