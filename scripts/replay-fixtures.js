#!/usr/bin/env node
// Post each fixtures/*.json to the local API's /webhooks/github endpoint.
// Useful for exercising the ingestion → scoring → realtime pipeline against
// a local instance without a real GitHub App.
//
// SAFETY:
//  - Refuses when NODE_ENV=production.
//  - Refuses when API_URL points at anything other than localhost / 127.0.0.1
//    unless FORCE=yes is set (protects against typing a prod URL by mistake).
//  - Requires the target API to accept unsigned webhooks (dev mode).

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const API = process.env.API_URL || 'http://localhost:4000';

if (process.env.NODE_ENV === 'production') {
  console.error('[replay] refusing to run with NODE_ENV=production');
  process.exit(1);
}

const parsed = new URL(API);
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);
if (!localHosts.has(parsed.hostname) && process.env.FORCE !== 'yes') {
  console.error(`[replay] API_URL points at "${parsed.hostname}" which is not localhost. Set FORCE=yes if you really mean it.`);
  process.exit(1);
}

async function main() {
  const files = fs.readdirSync(FIXTURES).filter(f => f.endsWith('.json')).sort();
  for (const f of files) {
    const { event, payload } = JSON.parse(fs.readFileSync(path.join(FIXTURES, f), 'utf8'));
    const res = await fetch(`${API}/webhooks/github`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-github-event': event,
        'x-github-delivery': crypto.randomUUID()
      },
      body: JSON.stringify(payload)
    });
    if (res.status === 401) {
      const body = await res.text().catch(() => '');
      console.error(`[replay] ${f} → 401. The target API rejected an unsigned webhook. Start the API with ALLOW_UNSIGNED_WEBHOOKS=true for local replay. Body: ${body}`);
      process.exit(1);
    }
    console.log(`[replay] ${f} → ${res.status}`);
    await new Promise(r => setTimeout(r, 300));
  }
}

main().catch(err => { console.error(err); process.exit(1); });
