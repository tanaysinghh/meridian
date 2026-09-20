package com.meridian.api.pullrequests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What actually happened after a PR merged.
 *
 * <p>One row per PR — this is the label side of the training set. Everything upstream predicts risk;
 * this is the only place that records whether the prediction was right, which is what makes
 * retraining on real outcomes possible instead of the synthetic distribution the model ships with.
 */
@Entity
@Table(name = "pr_outcomes")
public class PrOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pr_id", nullable = false, unique = true)
    private UUID prId;

    @Column(name = "reverted", nullable = false)
    private boolean reverted;

    @Column(name = "hotfixed", nullable = false)
    private boolean hotfixed;

    @Column(name = "caused_incident", nullable = false)
    private boolean causedIncident;

    @Column(name = "outcome_notes")
    private String outcomeNotes;

    @Column(name = "observed_at", nullable = false, insertable = false, updatable = true)
    private Instant observedAt;

    protected PrOutcome() {
    }

    public PrOutcome(UUID prId) {
        this.prId = prId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrId() {
        return prId;
    }

    public boolean isReverted() {
        return reverted;
    }

    public void setReverted(boolean reverted) {
        this.reverted = reverted;
    }

    public boolean isHotfixed() {
        return hotfixed;
    }

    public void setHotfixed(boolean hotfixed) {
        this.hotfixed = hotfixed;
    }

    public boolean isCausedIncident() {
        return causedIncident;
    }

    public void setCausedIncident(boolean causedIncident) {
        this.causedIncident = causedIncident;
    }

    public String getOutcomeNotes() {
        return outcomeNotes;
    }

    public void setOutcomeNotes(String outcomeNotes) {
        this.outcomeNotes = outcomeNotes;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
