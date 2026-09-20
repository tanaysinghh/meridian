package com.meridian.api.orgs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-org knobs, keyed by (and sharing a primary key with) the org itself.
 *
 * <p>The {@code TEXT[]} columns map to {@code List<String>} via {@link SqlTypes#ARRAY}; Postgres
 * arrays are what the previous service wrote and what {@code unnest(...)} in the reviewer-load
 * queries still reads, so they stay as arrays rather than becoming a join table.
 */
@Entity
@Table(name = "org_settings")
public class OrgSettings {

    /** Shares the org's id — this is a 1:1 extension table, not an independent entity. */
    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "slack_webhook_url")
    private String slackWebhookUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "digest_recipients", nullable = false)
    private List<String> digestRecipients = new ArrayList<>();

    @Column(name = "high_risk_sla_hours", nullable = false)
    private int highRiskSlaHours = 8;

    @Column(name = "auto_escalate", nullable = false)
    private boolean autoEscalate = true;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "notify_on_tiers", nullable = false)
    private List<String> notifyOnTiers = new ArrayList<>(List.of("high", "critical"));

    protected OrgSettings() {
    }

    public OrgSettings(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getSlackWebhookUrl() {
        return slackWebhookUrl;
    }

    public void setSlackWebhookUrl(String slackWebhookUrl) {
        this.slackWebhookUrl = slackWebhookUrl;
    }

    public List<String> getDigestRecipients() {
        return digestRecipients;
    }

    public void setDigestRecipients(List<String> digestRecipients) {
        this.digestRecipients = digestRecipients;
    }

    public int getHighRiskSlaHours() {
        return highRiskSlaHours;
    }

    public void setHighRiskSlaHours(int highRiskSlaHours) {
        this.highRiskSlaHours = highRiskSlaHours;
    }

    public boolean isAutoEscalate() {
        return autoEscalate;
    }

    public void setAutoEscalate(boolean autoEscalate) {
        this.autoEscalate = autoEscalate;
    }

    public List<String> getNotifyOnTiers() {
        return notifyOnTiers;
    }

    public void setNotifyOnTiers(List<String> notifyOnTiers) {
        this.notifyOnTiers = notifyOnTiers;
    }
}
