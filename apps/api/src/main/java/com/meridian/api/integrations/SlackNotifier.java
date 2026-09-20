package com.meridian.api.integrations;

import com.meridian.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Posts risk alerts to a Slack incoming webhook.
 *
 * <p>Two sources for the URL, org-level first then the environment default, matching
 * {@code orgSlackUrl || config.slack.webhookUrl}. When neither is set delivery is skipped and logged
 * rather than treated as an error — Slack alerting is optional, and the rest of the scoring pipeline
 * must not depend on it.
 *
 * <p>Failures are swallowed for the same reason. A Slack outage should not fail webhook ingestion.
 */
@Service
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final AppProperties props;
    private final RestClient restClient;

    public SlackNotifier(AppProperties props, RestClient.Builder builder) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restClient = builder.requestFactory(factory).build();
    }

    public Result notify(String orgSlackUrl, String text, List<Map<String, Object>> blocks) {
        String url = orgSlackUrl != null && !orgSlackUrl.isBlank()
                ? orgSlackUrl
                : props.slack().webhookUrl();

        if (url == null || url.isBlank()) {
            log.debug("slack_skipped_no_webhook");
            return new Result(false, "no_webhook_configured");
        }

        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "blocks", blocks))
                    .retrieve()
                    .toBodilessEntity();
            return new Result(true, null);
        } catch (Exception ex) {
            // The URL itself is a secret, so only the failure class is logged.
            log.warn("slack_delivery_failed reason={}", ex.getClass().getSimpleName());
            return new Result(false, ex.getClass().getSimpleName());
        }
    }

    /** Block Kit payload for a risky-PR alert. */
    public static List<Map<String, Object>> riskPrBlocks(String repoFullName,
                                                         int number,
                                                         String title,
                                                         String authorLogin,
                                                         String url,
                                                         int additions,
                                                         int deletions,
                                                         int changedFiles,
                                                         String tier,
                                                         Object score) {
        String headline = "*%s risk PR* in `%s`%n<%s|#%d — %s>%nby %s · score %s".formatted(
                tier.toUpperCase(java.util.Locale.ROOT), repoFullName, url, number, title, authorLogin, score);

        String context = "+%d / -%d across %d files".formatted(additions, deletions, changedFiles);

        return List.of(
                Map.of("type", "section",
                        "text", Map.of("type", "mrkdwn", "text", headline)),
                Map.of("type", "context",
                        "elements", List.of(Map.of("type", "mrkdwn", "text", context))));
    }

    public record Result(boolean delivered, String reason) {
    }
}
