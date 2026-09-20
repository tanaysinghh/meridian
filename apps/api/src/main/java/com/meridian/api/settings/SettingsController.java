package com.meridian.api.settings;

import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.config.AppProperties;
import com.meridian.api.orgs.OrgSettings;
import com.meridian.api.orgs.OrgSettingsRepository;
import com.meridian.api.settings.dto.SettingsDtos;
import com.meridian.api.users.OrgMemberDto;
import com.meridian.api.users.Role;
import com.meridian.api.users.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /settings} — org configuration, the team roster, and integration status.
 *
 * <p>Reading is open to the org; both write endpoints are admin-only, as before. The integration
 * flags are computed from configuration rather than stored, and are booleans by design — the
 * settings page needs to know whether Slack is wired up, not what the webhook URL is.
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final OrgSettingsRepository orgSettings;
    private final UserRepository users;
    private final AppProperties props;

    public SettingsController(OrgSettingsRepository orgSettings, UserRepository users, AppProperties props) {
        this.orgSettings = orgSettings;
        this.users = users;
        this.props = props;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> get(@AuthenticationPrincipal AuthenticatedUser user) {
        Optional<OrgSettings> settings = orgSettings.findById(user.orgId());

        List<OrgMemberDto> roster = users.findByOrgIdOrderByRoleAscNameAsc(user.orgId()).stream()
                .map(OrgMemberDto::from)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        // An org with no settings row yet returns {} here, not null — the frontend reads fields off
        // this object directly and the previous handler's `rows[0] || {}` did the same.
        body.put("settings", settings.<Object>map(SettingsController::toDto).orElseGet(Map::of));
        body.put("users", roster);
        body.put("integrations", new SettingsDtos.Integrations(
                !props.github().appId().isBlank(),
                !props.github().clientId().isBlank() && !props.github().clientSecret().isBlank(),
                !props.github().webhookSecret().isBlank(),
                !props.slack().webhookUrl().isBlank()
                        || settings.map(s -> s.getSlackWebhookUrl() != null && !s.getSlackWebhookUrl().isBlank())
                        .orElse(false),
                !props.email().provider().isBlank()));
        return body;
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody SettingsDtos.Update body) {

        // Upsert: an org that has never had settings saved gets a row with the schema defaults,
        // then the supplied fields applied over them.
        OrgSettings settings = orgSettings.findById(user.orgId())
                .orElseGet(() -> new OrgSettings(user.orgId()));

        if (body.slackWebhookUrl() != null) {
            settings.setSlackWebhookUrl(body.slackWebhookUrl());
        }
        if (body.digestRecipients() != null) {
            settings.setDigestRecipients(new ArrayList<>(body.digestRecipients()));
        }
        if (body.highRiskSlaHours() != null) {
            settings.setHighRiskSlaHours(body.highRiskSlaHours());
        }
        if (body.autoEscalate() != null) {
            settings.setAutoEscalate(body.autoEscalate());
        }
        if (body.notifyOnTiers() != null) {
            settings.setNotifyOnTiers(new ArrayList<>(body.notifyOnTiers()));
        }

        orgSettings.save(settings);
        return Map.of("ok", true);
    }

    @PatchMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> updateRole(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody SettingsDtos.RoleUpdate body) {
        // Scoped to the caller's org — a user id from elsewhere matches nothing.
        users.updateRole(id, user.orgId(), Role.from(body.role()));
        return Map.of("ok", true);
    }

    private static SettingsDtos.OrgSettingsDto toDto(OrgSettings settings) {
        return new SettingsDtos.OrgSettingsDto(
                settings.getOrgId(),
                settings.getSlackWebhookUrl(),
                settings.getDigestRecipients(),
                settings.getHighRiskSlaHours(),
                settings.isAutoEscalate(),
                settings.getNotifyOnTiers());
    }
}
