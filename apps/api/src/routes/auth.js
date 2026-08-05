import { Router } from 'express';
import bcrypt from 'bcryptjs';
import crypto from 'node:crypto';
import { query } from '../db/pool.js';
import { signAccess } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';

export const authRoutes = Router();

const COOKIE_OPTS = {
  httpOnly: true,
  sameSite: 'lax',
  secure: process.env.NODE_ENV === 'production',
  path: '/'
};

function setAuthCookies(res, user, refreshToken) {
  res.cookie('mrd_at', signAccess(user), { ...COOKIE_OPTS, maxAge: 15 * 60 * 1000 });
  res.cookie('mrd_rt', refreshToken, { ...COOKIE_OPTS, maxAge: 30 * 24 * 3600 * 1000 });
}

async function issueRefresh(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  const expires = new Date(Date.now() + 30 * 24 * 3600 * 1000);
  await query(
    'INSERT INTO sessions (user_id, refresh_token, expires_at) VALUES ($1,$2,$3)',
    [userId, token, expires]
  );
  return token;
}

authRoutes.post('/login', async (req, res, next) => {
  try {
    const { email, password } = req.body || {};
    if (!email || !password) throw httpError(400, 'email_and_password_required');
    const { rows } = await query(
      'SELECT id, org_id, email, name, role, github_login, avatar_url, password_hash FROM users WHERE email = $1',
      [email.toLowerCase()]
    );
    const user = rows[0];
    if (!user || !user.password_hash) throw httpError(401, 'invalid_credentials');
    const ok = await bcrypt.compare(password, user.password_hash);
    if (!ok) throw httpError(401, 'invalid_credentials');

    const refresh = await issueRefresh(user.id);
    setAuthCookies(res, user, refresh);
    delete user.password_hash;
    res.json({ user });
  } catch (err) { next(err); }
});

authRoutes.post('/logout', async (req, res) => {
  const rt = req.cookies?.mrd_rt;
  if (rt) await query('DELETE FROM sessions WHERE refresh_token = $1', [rt]);
  res.clearCookie('mrd_at');
  res.clearCookie('mrd_rt');
  res.json({ ok: true });
});

// GitHub OAuth — real flow when GITHUB_CLIENT_ID is set, otherwise a dev shortcut
// that logs in as the demo admin so the UI is exercisable end-to-end.
authRoutes.get('/github', async (req, res, next) => {
  try {
    if (!process.env.GITHUB_CLIENT_ID) {
      // dev-mode shortcut: log in as demo admin
      const { rows } = await query(
        `SELECT id, org_id, email, name, role, github_login, avatar_url FROM users WHERE email = 'demo@meridian.dev'`
      );
      if (!rows[0]) throw httpError(500, 'seed_user_missing');
      const refresh = await issueRefresh(rows[0].id);
      setAuthCookies(res, rows[0], refresh);
      const redirect = req.query.redirect || (process.env.WEB_ORIGIN + '/app');
      return res.redirect(redirect);
    }
    // TODO(external): real GitHub OAuth authorize redirect
    const url = new URL('https://github.com/login/oauth/authorize');
    url.searchParams.set('client_id', process.env.GITHUB_CLIENT_ID);
    url.searchParams.set('redirect_uri', process.env.GITHUB_OAUTH_CALLBACK);
    url.searchParams.set('scope', 'read:user user:email');
    res.redirect(url.toString());
  } catch (err) { next(err); }
});

authRoutes.get('/github/callback', async (_req, res) => {
  // TODO(external): exchange code, fetch user, upsert user row, issue session
  res.status(501).json({ error: 'github_oauth_not_configured' });
});
