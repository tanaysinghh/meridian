import pg from 'pg';
import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';

const { Pool } = pg;

export const pool = new Pool({
  connectionString: config.databaseUrl,
  max: config.dbPoolMax,
  idleTimeoutMillis: config.dbPoolIdleMs,
  connectionTimeoutMillis: 10_000,
  ssl: config.databaseSsl ? { rejectUnauthorized: false } : false
});

pool.on('error', err => {
  logger.error({ err: { message: err.message, stack: err.stack } }, 'pg_pool_error');
});

export async function query(text, params) {
  return pool.query(text, params);
}

export async function tx(fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

export async function healthCheck() {
  const { rows } = await pool.query('SELECT 1 AS ok');
  return rows[0]?.ok === 1;
}
