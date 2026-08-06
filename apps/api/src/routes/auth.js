import { Router } from 'express';
import bcrypt from 'bcryptjs';
import crypto from 'node:crypto';
import { z } from 'zod';
import { request } from 'undici';
import { query } from '../db/pool.js';
import { signAccess } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';
import { validate } from '../utils/validate.js';
import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';
import { issueCsrfCookie } from '../middleware/csrf.js';

export const authRoutes = Router();

const COOKIE_OPTS = {
  httpOnly: true,
  sameSite: 'lax',
  secure: config.isProd,
  path: '/'
};

function setAuthCookies(res, user, refreshToken) {
  res.cookie('mrd_at', signAccess(user), { ...COOKIE_OPTS, maxAge: 15 * 60 * 1000 });
  res.cookie('mrd_rt', refreshToken, {
    ...COOKIE_OPTS,
    maxAge: config.jwtRefreshTtlDays * 24 * 3600 * 1000
  });
  issueCsrfCookie(res);
}

function clearAuthCookies(res) {
  res.clearCookie('mrd_at', { path: '/' });
  res.clearCookie('mrd_rt', { path: '/' });
  res.clearCookie('mrd_csrf', { path: '/' });
}

async function issueRefresh(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  const expires = new Date(Date.now() + config.jwtRefreshTtlDays * 24 * 3600 * 1000);
  await query(
    'INSERT INTO sessions (user_id, refresh_token, expires_at) VALUES ($1,$2,$3)',
    [userId, token, expires]);
  return token;
}

const loginSchema = z.object({
  email: z.string().trim().toLowerCase().email().max(320),
  password: z.string().min(1).max(200)
});

authRoutes.post('/login', validate({ body: loginSchema }), async (req, res, next) => {
  try {
    const { email, password } = req.body;
    const { rows } = await query(
      'SELECT id, org_id, email, name, role, github_login, avatar_url, password_hash FROM users WHERE email = $1',
      [email]);
    const user = rows[0];
    // Constant-ish work whether or not the user exists to avoid trivial user-enumeration.
    // 60-char well-formed bcrypt hash of a random string; compare wastes ~same time as a real match.
    const hashToCompare = user?.password_hash || '$2a$12$abcdefghijklmnopqrstuuMh1cnPRfMYtnU8H8mZfOpVjM.eYkxHK';
    const ok = await bcrypt.compare(password, hashToCompare);
    if (!user || !user.password_hash || !ok) throw httpError(401, 'invalid_credentials');

    const refresh = await issueRefresh(user.id);
    setAuthCookies(res, user, refresh);
    delete user.password_hash;
    res.json({ user });
  } catch (err) { next(err); }
});

authRoutes.post('/logout', async (req, res, next) => {
  try {
    const rt = req.cookies?.mrd_rt;
    if (rt) await query('DELETE FROM sessions WHERE refresh_token = $1', [rt]);
    clearAuthCookies(res);
    res.json({ ok: true });
  } catch (err) { next(err); }
});

authRoutes.post('/refresh', async (req, res, next) => {
  try {
    const rt = req.cookies?.mrd_rt;
    if (!rt) throw httpError(401, 'no_refresh_token');
    const { rows } = await query(
      `SELECT s.user_id, s.expires_at, u.id, u.org_id, u.email, u.name, u.role, u.github_login, u.avatar_url
         FROM sessions s JOIN users u ON u.id = s.user_id
        WHERE s.refresh_token = $1`,
      [rt]);
    const row = rows[0];
    if (!row || new Date(row.expires_at) < new Date()) {
      if (row) await query('DELETE FROM sessions WHERE refresh_token = $1', [rt]);
      throw httpError(401, 'refresh_expired');
    }
    // Rotate: issue a new refresh token, delete the old one.
    await query('DELETE FROM sessions WHERE refresh_token = $1', [rt]);
    const nextRt = await issueRefresh(row.user_id);
    setAuthCookies(res, row, nextRt);
    res.json({ ok: true });
  } catch (err) { next(err); }
});

// --- GitHub OAuth ---------------------------------------------------------
// Real flow. If credentials are unset, returns a clear 503 — never silently
// logs in as a demo user.
const OAUTH_STATE_COOKIE = 'mrd_oauth_state';

function githubConfigured() {
  return !!(config.github.clientId && config.github.clientSecret && config.github.oauthCallback);
}

authRoutes.get('/github', (req, res, next) => {
  try {
    if (!githubConfigured()) {
      return res.status(503).json({
        error: 'github_oauth_not_configured',
        message: 'GitHub OAuth is not configured on this server. Set GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, and GITHUB_OAUTH_CALLBACK.'
      });
    }
    const state = crypto.randomBytes(24).toString('hex');
    res.cookie(OAUTH_STATE_COOKIE, state, {
      httpOnly: true, sameSite: 'lax', secure: config.isProd, path: '/', maxAge: 10 * 60 * 1000
    });
    const url = new URL('https://github.com/login/oauth/authorize');
    url.searchParams.set('client_id', config.github.clientId);
    url.searchParams.set('redirect_uri', config.github.oauthCallback);
    url.searchParams.set('scope', 'read:user user:email');
    url.searchParams.set('state', state);
    res.redirect(url.toString());
  } catch (err) { next(err); }
});

const callbackSchema = z.object({
  code: z.string().min(1).max(500),
  state: z.string().min(1).max(200)
});

authRoutes.get('/github/callback', validate({ query: callbackSchema }), async (req, res, next) => {
  try {
    if (!githubConfigured()) {
      return res.status(503).json({ error: 'github_oauth_not_configured' });
    }
    const expectedState = req.cookies?.[OAUTH_STATE_COOKIE];
    if (!expectedState || expectedState !== req.query.state) {
      throw httpError(400, 'invalid_oauth_state');
    }
    res.clearCookie(OAUTH_STATE_COOKIE, { path: '/' });

    const tokenRes = await request('https://github.com/login/oauth/access_token', {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/json' },
      body: JSON.stringify({
        client_id: config.github.clientId,
        client_secret: config.github.clientSecret,
        code: req.query.code,
        redirect_uri: config.github.oauthCallback
      }),
      bodyTimeout: 10_000
    });
    const tokenBody = await tokenRes.body.json();
    const accessToken = tokenBody?.access_token;
    if (!accessToken) {
      logger.warn({ tokenBody: { error: tokenBody?.error } }, 'github_oauth_token_exchange_failed');
      throw httpError(401, 'github_oauth_failed');
    }

    const [userRes, emailsRes] = await Promise.all([
      request('https://api.github.com/user', {
        headers: { authorization: `Bearer ${accessToken}`, 'user-agent': 'meridian', accept: 'application/vnd.github+json' },
        bodyTimeout: 10_000
      }),
      request('https://api.github.com/user/emails', {
        headers: { authorization: `Bearer ${accessToken}`, 'user-agent': 'meridian', accept: 'application/vnd.github+json' },
        bodyTimeout: 10_000
      })
    ]);
    const ghUser = await userRes.body.json();
    const emails = await emailsRes.body.json();
    const primary = Array.isArray(emails) ? emails.find(e => e.primary && e.verified) : null;
    const email = (primary?.email || ghUser?.email || '').toLowerCase();
    if (!ghUser?.id || !email) throw httpError(401, 'github_no_verified_email');

    // Upsert into the first org (single-tenant deploy). Multi-tenant would map on installation id.
    const orgRow = (await query('SELECT id FROM orgs ORDER BY created_at LIMIT 1')).rows[0];
    if (!orgRow) throw httpError(500, 'no_org_provisioned');

    const upsert = await query(
      `INSERT INTO users (org_id, email, name, github_login, github_id, avatar_url, role)
       VALUES ($1,$2,$3,$4,$5,$6,'developer')
       ON CONFLICT (email) DO UPDATE SET
         name = EXCLUDED.name,
         github_login = EXCLUDED.github_login,
         github_id = EXCLUDED.github_id,
         avatar_url = EXCLUDED.avatar_url
       RETURNING id, org_id, email, name, role, github_login, avatar_url`,
      [orgRow.id, email, ghUser.name || ghUser.login, ghUser.login, ghUser.id, ghUser.avatar_url]);
    const user = upsert.rows[0];

    const refresh = await issueRefresh(user.id);
    setAuthCookies(res, user, refresh);
    res.redirect(config.webOrigin + '/app');
  } catch (err) { next(err); }
});
