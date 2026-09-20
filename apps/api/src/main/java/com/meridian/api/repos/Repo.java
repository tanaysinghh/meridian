package com.meridian.api.repos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A connected GitHub repository.
 *
 * <p>Identified by {@code (org_id, full_name)} rather than by GitHub's numeric id, because the
 * webhook path has to be able to create a repo row the first time it sees a delivery — before
 * anyone has explicitly connected it — and {@code full_name} is what that payload always carries.
 */
@Entity
@Table(name = "repos")
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "github_id", unique = true)
    private Long githubId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    /** Tier-boundary tuning knob, 0..1. NUMERIC(4,3) in the schema. */
    @Column(name = "risk_threshold", nullable = false, precision = 4, scale = 3)
    private BigDecimal riskThreshold = new BigDecimal("0.600");

    @Column(name = "connected_at", nullable = false, insertable = false, updatable = false)
    private Instant connectedAt;

    protected Repo() {
    }

    public Repo(UUID orgId, String fullName, String defaultBranch) {
        this.orgId = orgId;
        this.fullName = fullName;
        this.defaultBranch = defaultBranch == null ? "main" : defaultBranch;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Long getGithubId() {
        return githubId;
    }

    public void setGithubId(Long githubId) {
        this.githubId = githubId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public BigDecimal getRiskThreshold() {
        return riskThreshold;
    }

    public void setRiskThreshold(BigDecimal riskThreshold) {
        this.riskThreshold = riskThreshold;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }
}
