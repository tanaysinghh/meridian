package com.meridian.api.rules;

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
 * A per-repo escalation rule: a predicate, and the tier to raise a PR to when it matches.
 *
 * <p>{@code predicate} and {@code action} stay as raw JSON because the predicate is a discriminated
 * union whose arms differ in shape ({@code path_glob} carries a glob, {@code author_in} carries a
 * list, and so on). The API layer validates the incoming shape before it is ever written — see
 * {@code RuleRequests} — so what reaches this table has already been checked, and the evaluator can
 * read it back without a per-arm entity hierarchy.
 */
@Entity
@Table(name = "repo_rules")
public class RepoRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "predicate", nullable = false)
    private String predicate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected RepoRule() {
    }

    public RepoRule(UUID repoId, String name, boolean enabled, String predicate, String action) {
        this.repoId = repoId;
        this.name = name;
        this.enabled = enabled;
        this.predicate = predicate;
        this.action = action;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
