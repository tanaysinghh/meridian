#!/usr/bin/env node
// Post each fixtures/*.json to the local API's /webhooks/github endpoint.
// Useful for demoing the full ingestion → scoring → realtime pipeline
// without needing a real GitHub App wired up.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const API = process.env.API_URL || 'http://localhost:4000';

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
    console.log(`[replay] ${f} → ${res.status}`);
    await new Promise(r => setTimeout(r, 300));
  }
}

main().catch(err => { console.error(err); process.exit(1); });
