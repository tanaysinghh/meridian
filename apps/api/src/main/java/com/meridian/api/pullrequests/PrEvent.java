package com.meridian.api.pullrequests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only record of something that happened to a PR.
 *
 * <p>The raw webhook delivery is kept in {@code payload} so a scoring decision can be re-derived
 * later against a changed feature set — the PR row itself only holds the current state.
 *
 * <p>{@code payload} is held as the raw JSON text rather than a parsed structure. Nothing in the
 * application reads individual fields back out of it, and keeping the document verbatim means a
 * change to GitHub's payload shape cannot break persistence.
 */
@Entity
@Table(name = "pr_events")
public class PrEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pr_id", nullable = false)
    private UUID prId;

    /** e.g. {@code pull_request.opened}, {@code review.submitted}. */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "actor_login")
    private String actorLogin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    protected PrEvent() {
    }

    public PrEvent(UUID prId, String eventType, String actorLogin, String payload) {
        this.prId = prId;
        this.eventType = eventType;
        this.actorLogin = actorLogin;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrId() {
        return prId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActorLogin() {
        return actorLogin;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
