package com.meridian.api.settings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request and response shapes for {@code /settings}. */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    /** The org's stored settings, as returned under the {@code settings} key. */
    @JsonPropertyOrder({"org_id", "slack_webhook_url", "digest_recipients",
            "high_risk_sla_hours", "auto_escalate", "notify_on_tiers"})
    public record OrgSettingsDto(
            @JsonProperty("org_id") UUID orgId,
            @JsonProperty("slack_webhook_url") String slackWebhookUrl,
            @JsonProperty("digest_recipients") List<String> digestRecipients,
            @JsonProperty("high_risk_sla_hours") int highRiskSlaHours,
            @JsonProperty("auto_escalate") boolean autoEscalate,
            @JsonProperty("notify_on_tiers") List<String> notifyOnTiers
    ) {}

    /**
     * Which integrations are wired up. Booleans only — this tells the settings page what to show as
     * connected without ever disclosing the credentials behind them.
     */
    @JsonPropertyOrder({"github_app_configured", "github_oauth_configured", "webhook_secret_configured",
            "slack_configured", "email_configured"})
    public record Integrations(
            @JsonProperty("github_app_configured") boolean githubAppConfigured,
            @JsonProperty("github_oauth_configured") boolean githubOauthConfigured,
            @JsonProperty("webhook_secret_configured") boolean webhookSecretConfigured,
            @JsonProperty("slack_configured") boolean slackConfigured,
            @JsonProperty("email_configured") boolean emailConfigured
    ) {}

    /**
     * Body of {@code PATCH /settings}. Every field is optional; absent fields are left alone.
     *
     * <p>The Slack URL constraint is a deliberate guard, not incidental validation: an incoming
     * webhook URL is a capability, and without the host check an operator could be talked into
     * pointing org notifications — which include PR titles and risk context — at an arbitrary
     * server.
     */
    public record Update(
            @JsonProperty("slack_webhook_url")
            @Size(max = 500, message = "String must contain at most 500 character(s)")
            @Pattern(regexp = "https://hooks\\.slack\\.com/.*", message = "must be a hooks.slack.com URL")
            String slackWebhookUrl,

            @JsonProperty("digest_recipients")
            @Size(max = 50, message = "Array must contain at most 50 element(s)")
            List<@Email(message = "Invalid email") @Size(max = 320) String> digestRecipients,

            @JsonProperty("high_risk_sla_hours")
            @Positive(message = "Number must be greater than 0")
            @Max(value = 720, message = "Number must be less than or equal to 720")
            Integer highRiskSlaHours,

            @JsonProperty("auto_escalate") Boolean autoEscalate,

            @JsonProperty("notify_on_tiers")
            @Size(max = 4, message = "Array must contain at most 4 element(s)")
            List<@Pattern(regexp = "low|medium|high|critical", message = "Invalid enum value") String> notifyOnTiers
    ) {}

    /** Body of {@code PATCH /settings/users/{id}/role}. */
    public record RoleUpdate(
            @JsonProperty("role")
            @NotNull(message = "Required")
            @Pattern(regexp = "developer|team_lead|admin", message = "Invalid enum value")
            String role
    ) {}
}
