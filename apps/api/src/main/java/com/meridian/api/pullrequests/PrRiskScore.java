package com.meridian.api.pullrequests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One scoring run against one PR.
 *
 * <p>Rows accumulate rather than being replaced — a PR scored again after a push keeps its earlier
 * scores, and "the" score is always the most recent by {@code scored_at}. That history is what makes
 * it possible to ask later whether the model's opinion moved before a revert.
 *
 * <p>{@code features}, {@code contributions} and {@code ruleHits} are stored as raw JSON text. They
 * are written once and only ever read back to be handed to the client verbatim, so parsing them into
 * Java types would add a lossy round-trip and couple this table to the ML service's output shape.
 */
@Entity
@Table(name = "pr_risk_scores")
public class PrRiskScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pr_id", nullable = false)
    private UUID prId;

    /** 0..1, NUMERIC(4,3). */
    @Column(name = "score", nullable = false, precision = 4, scale = 3)
    private BigDecimal score;

    /** Final tier after rule escalation — not necessarily the model's own tier. */
    @Column(name = "tier", nullable = false)
    private String tier;

    @Column(name = "confidence", nullable = false)
    private String confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false)
    private String features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contributions", nullable = false)
    private String contributions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_hits", nullable = false)
    private String ruleHits = "[]";

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "scored_at", nullable = false, insertable = false, updatable = false)
    private Instant scoredAt;

    protected PrRiskScore() {
    }

    public PrRiskScore(UUID prId,
                       BigDecimal score,
                       String tier,
                       String confidence,
                       String features,
                       String contributions,
                       String ruleHits,
                       String modelVersion) {
        this.prId = prId;
        this.score = score;
        this.tier = tier;
        this.confidence = confidence;
        this.features = features;
        this.contributions = contributions;
        this.ruleHits = ruleHits == null ? "[]" : ruleHits;
        this.modelVersion = modelVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrId() {
        return prId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getTier() {
        return tier;
    }

    public String getConfidence() {
        return confidence;
    }

    public String getFeatures() {
        return features;
    }

    public String getContributions() {
        return contributions;
    }

    public String getRuleHits() {
        return ruleHits;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Instant getScoredAt() {
        return scoredAt;
    }
}
