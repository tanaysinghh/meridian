import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pool } from './pool.js';
import { config } from '../utils/env.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

async function main() {
  const reset = process.argv.includes('--reset');
  if (reset) {
    // Guard: never drop schema in production without an explicit CONFIRM_RESET=yes flag.
    if (config.isProd && process.env.CONFIRM_RESET !== 'yes') {
      console.error('[migrate] refusing to reset production DB without CONFIRM_RESET=yes');
      process.exit(1);
    }
    console.log('[migrate] dropping public schema');
    await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
  }
  const sql = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8');
  // schema.sql uses CREATE ... IF NOT EXISTS everywhere → idempotent, safe to re-run.
  await pool.query(sql);
  console.log('[migrate] schema applied');
  await pool.end();
}

main().catch(err => { console.error(err); process.exit(1); });
