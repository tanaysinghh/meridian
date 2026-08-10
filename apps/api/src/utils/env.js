import 'dotenv/config';

const NODE_ENV = process.env.NODE_ENV || 'development';
const isProd = NODE_ENV === 'production';

function required(name, { prodOnly = false } = {}) {
  const v = process.env[name];
  if (!v && (prodOnly ? isProd : true)) {
    if (isProd) {
      throw new Error(`[config] missing required env var: ${name}`);
    }
  }
  return v;
}

function num(name, fallback) {
  const v = process.env[name];
  if (v == null || v === '') return fallback;
  const n = Number(v);
  if (!Number.isFinite(n)) throw new Error(`[config] ${name} must be a number`);
  return n;
}

function bool(name, fallback = false) {
  const v = process.env[name];
  if (v == null || v === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase());
}

function csv(name, fallback = []) {
  const v = process.env[name];
  if (!v) return fallback;
  return v.split(',').map(s => s.trim()).filter(Boolean);
}

// JWT_SECRET is required in prod; in dev we allow a marked fallback but log loudly.
let JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  if (isProd) throw new Error('[config] JWT_SECRET is required in production');
  JWT_SECRET = 'dev-only-insecure-jwt-secret-do-not-use-in-prod';
}
if (isProd && JWT_SECRET.length < 32) {
  throw new Error('[config] JWT_SECRET must be at least 32 chars in production');
}

if (isProd && !process.env.DATABASE_URL) {
  throw new Error('[config] DATABASE_URL is required in production');
}

export const config = {
  env: NODE_ENV,
  isProd,
  isDev: NODE_ENV === 'development',
  isTest: NODE_ENV === 'test',
  port: num('PORT', 4000),
  webOrigin: process.env.WEB_ORIGIN || 'http://localhost:5173',
  allowedOrigins: csv('ALLOWED_ORIGINS', [process.env.WEB_ORIGIN || 'http://localhost:5173']),
  databaseUrl: process.env.DATABASE_URL,
  databaseSsl: bool('DATABASE_SSL', isProd),
  dbPoolMax: num('DB_POOL_MAX', isProd ? 20 : 10),
  dbPoolIdleMs: num('DB_POOL_IDLE_MS', 30_000),
  jwtSecret: JWT_SECRET,
  jwtAccessTtl: process.env.JWT_ACCESS_TTL || '15m',
  jwtRefreshTtlDays: num('JWT_REFRESH_TTL_DAYS', 30),
  bcryptCost: num('BCRYPT_COST', 12),
  mlServiceUrl: process.env.ML_SERVICE_URL || 'http://localhost:8000',
  mlInternalSecret: process.env.ML_INTERNAL_SECRET || '',
  github: {
    clientId: process.env.GITHUB_CLIENT_ID || '',
    clientSecret: process.env.GITHUB_CLIENT_SECRET || '',
    oauthCallback: process.env.GITHUB_OAUTH_CALLBACK || '',
    webhookSecret: process.env.GITHUB_WEBHOOK_SECRET || '',
    appId: process.env.GITHUB_APP_ID || '',
    // In prod, the webhook secret must be set — verification is never bypassed.
    // In dev, an explicit opt-in disables verification for local fixture replay.
    allowUnsignedWebhooks: !isProd && bool('ALLOW_UNSIGNED_WEBHOOKS', false)
  },
  slack: { webhookUrl: process.env.SLACK_WEBHOOK_URL || '' },
  email: {
    provider: process.env.EMAIL_PROVIDER || '',
    from: process.env.EMAIL_FROM || '',
    resendKey: process.env.RESEND_API_KEY || ''
  },
  rateLimit: {
    authWindowMs: num('RATE_LIMIT_AUTH_WINDOW_MS', 15 * 60 * 1000),
    authMax: num('RATE_LIMIT_AUTH_MAX', 10),
    apiWindowMs: num('RATE_LIMIT_API_WINDOW_MS', 60 * 1000),
    apiMax: num('RATE_LIMIT_API_MAX', 300)
  },
  logLevel: process.env.LOG_LEVEL || (isProd ? 'info' : 'debug'),
  trustProxy: bool('TRUST_PROXY', isProd)
};

// Sanity check: warn if webhook secret is missing outside of dev opt-in.
if (isProd && !config.github.webhookSecret) {
  throw new Error('[config] GITHUB_WEBHOOK_SECRET is required in production');
}
