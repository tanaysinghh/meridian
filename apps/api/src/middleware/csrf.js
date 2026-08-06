import crypto from 'node:crypto';
import { httpError } from './error.js';
import { config } from '../utils/env.js';

// Double-submit CSRF: the API mints a random token in a readable cookie; the
// browser echoes it back in the X-CSRF-Token header. Attackers on another
// origin can't read the cookie (SameSite=Lax + different origin) so they can't
// forge the header. We skip verification for GET/HEAD/OPTIONS and for the
// webhook route (verified by HMAC instead).
const COOKIE = 'mrd_csrf';
const HEADER = 'x-csrf-token';
const SAFE = new Set(['GET', 'HEAD', 'OPTIONS']);

export function issueCsrfCookie(res) {
  const token = crypto.randomBytes(24).toString('hex');
  res.cookie(COOKIE, token, {
    httpOnly: false,     // must be readable by our JS to echo it back
    sameSite: 'lax',
    secure: config.isProd,
    path: '/',
    maxAge: 24 * 3600 * 1000
  });
  return token;
}

export function csrfProtection(req, res, next) {
  if (SAFE.has(req.method)) {
    // Ensure a token exists for later state-changing calls in the same session.
    if (!req.cookies?.[COOKIE]) issueCsrfCookie(res);
    return next();
  }
  const cookie = req.cookies?.[COOKIE];
  const header = req.get(HEADER);
  if (!cookie || !header || cookie !== header) {
    return next(httpError(403, 'csrf_token_invalid'));
  }
  next();
}
