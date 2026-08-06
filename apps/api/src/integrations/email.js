import fs from 'node:fs';
import path from 'node:path';
import { request } from 'undici';
import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';

// Email delivery. Supported providers: 'resend'. When no provider is set,
// dev builds fall back to writing HTML into ./outbox/ so digests are
// inspectable. In production, no provider means email is disabled — the
// caller decides whether that's an error.
export async function sendEmail({ to, subject, html }) {
  const provider = config.email.provider.toLowerCase();

  if (!provider) {
    if (config.isProd) {
      logger.warn({ to }, 'email_disabled_no_provider');
      return { delivered: false, reason: 'no_provider_configured' };
    }
    const dir = path.resolve('outbox');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${Date.now()}-${to.replace(/[^\w]/g, '_')}.html`);
    fs.writeFileSync(file, `<!-- to: ${to} · subject: ${subject} -->\n${html}`);
    logger.info({ file, to }, 'email_dev_written');
    return { delivered: false, file };
  }

  if (provider === 'resend') {
    if (!config.email.resendKey) {
      throw new Error('[email] EMAIL_PROVIDER=resend but RESEND_API_KEY is missing');
    }
    if (!config.email.from) {
      throw new Error('[email] EMAIL_FROM is required when EMAIL_PROVIDER is set');
    }
    const res = await request('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        authorization: `Bearer ${config.email.resendKey}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({ from: config.email.from, to, subject, html }),
      bodyTimeout: 15_000
    });
    if (res.statusCode >= 400) {
      const body = await res.body.text();
      logger.warn({ status: res.statusCode, body }, 'resend_delivery_failed');
      return { delivered: false, reason: `resend_${res.statusCode}` };
    }
    return { delivered: true };
  }

  logger.warn({ provider }, 'email_provider_not_implemented');
  return { delivered: false, reason: 'provider_not_implemented' };
}
