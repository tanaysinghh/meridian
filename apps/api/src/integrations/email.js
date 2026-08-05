import fs from 'node:fs';
import path from 'node:path';

// Email delivery. In dev, writes to outbox/*.html so you can inspect the digest.
// TODO(external): wire up EMAIL_PROVIDER=resend|sendgrid|ses.
export async function sendEmail({ to, subject, html }) {
  const provider = process.env.EMAIL_PROVIDER;
  if (!provider) {
    const dir = path.resolve('outbox');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${Date.now()}-${to.replace(/[^\w]/g, '_')}.html`);
    fs.writeFileSync(file, `<!-- to: ${to} · subject: ${subject} -->\n${html}`);
    console.log('[email:dry-run] wrote', file);
    return { delivered: false, file };
  }
  // TODO(external): implement provider dispatch
  console.warn('[email] provider set but dispatch not implemented:', provider);
  return { delivered: false, reason: 'not_implemented' };
}
