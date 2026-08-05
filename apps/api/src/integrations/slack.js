import { request } from 'undici';

// Slack notifier. If SLACK_WEBHOOK_URL is not set, logs instead of sending —
// this lets the whole pipeline be exercised in local dev.
export async function notifySlack({ orgSlackUrl, text, blocks }) {
  const url = orgSlackUrl || process.env.SLACK_WEBHOOK_URL;
  if (!url) {
    console.log('[slack:dry-run]', text);
    return { delivered: false, reason: 'no_webhook_configured' };
  }
  try {
    await request(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text, blocks })
    });
    return { delivered: true };
  } catch (err) {
    console.warn('[slack] delivery failed:', err.message);
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
