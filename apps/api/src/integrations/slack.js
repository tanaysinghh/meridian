import { request } from 'undici';
import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';

// Slack notifier. If no webhook URL is configured (org- or env-level), skips
// delivery and logs a structured record — in production this means Slack
// alerting is off; in dev it lets the pipeline exercise end-to-end without a
// real webhook.
export async function notifySlack({ orgSlackUrl, text, blocks }) {
  const url = orgSlackUrl || config.slack.webhookUrl;
  if (!url) {
    logger.debug({ text }, 'slack_skipped_no_webhook');
    return { delivered: false, reason: 'no_webhook_configured' };
  }
  try {
    await request(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text, blocks }),
      bodyTimeout: 10_000
    });
    return { delivered: true };
  } catch (err) {
    logger.warn({ err: { message: err.message } }, 'slack_delivery_failed');
    return { delivered: false, reason: err.message };
  }
}

export function riskPRBlock(pr, score) {
  return [
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `*${score.tier.toUpperCase()} risk PR* in \`${pr.repo_full_name}\`\n<${pr.url}|#${pr.number} — ${pr.title}>\nby ${pr.author_login} · score ${score.score}`
      }
    },
    {
      type: 'context',
      elements: [{
        type: 'mrkdwn',
        text: `+${pr.additions} / -${pr.deletions} across ${pr.changed_files} files`
      }]
    }
  ];
}
