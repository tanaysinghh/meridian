package com.meridian.api.incidents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A production incident, optionally linked back to the PR that caused it.
 *
 * <p>That link is the point of the table: filing an incident against a PR also flips that PR's
 * {@code caused_incident} outcome, closing the loop between "we thought this was risky" and "it
 * was". Both the repo and PR references are nullable, because an incident is worth recording even
 * before anyone knows what caused it.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "repo_id")
    private UUID repoId;

    @Column(name = "related_pr_id")
    private UUID relatedPrId;

    @Column(name = "title", nullable = false)
    private String title;

    /** One of {@code sev1}..{@code sev4}. */
    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "description")
    private String description;

    @Column(name = "reported_by")
    private UUID reportedBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Incident() {
    }

    public Incident(UUID orgId, String title, String severity, Instant occurredAt) {
        this.orgId = orgId;
        this.title = title;
        this.severity = severity;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public void setRepoId(UUID repoId) {
        this.repoId = repoId;
    }

    public UUID getRelatedPrId() {
        return relatedPrId;
    }

    public void setRelatedPrId(UUID relatedPrId) {
        this.relatedPrId = relatedPrId;
    }

    public String getTitle() {
        return title;
    }

    public String getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(UUID reportedBy) {
        this.reportedBy = reportedBy;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
