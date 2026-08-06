import jwt from 'jsonwebtoken';
import { httpError } from './error.js';
import { query } from '../db/pool.js';
import { config } from '../utils/env.js';

export function signAccess(user) {
  return jwt.sign(
    { sub: user.id, org: user.org_id, role: user.role },
    config.jwtSecret,
    { expiresIn: config.jwtAccessTtl, issuer: 'meridian', audience: 'meridian-web' }
  );
}

export function verifyAccess(token) {
  return jwt.verify(token, config.jwtSecret, { issuer: 'meridian', audience: 'meridian-web' });
}

export async function requireAuth(req, _res, next) {
  try {
    const token = req.cookies?.mrd_at || (req.headers.authorization || '').replace('Bearer ', '');
    if (!token) throw httpError(401, 'unauthenticated');
    const decoded = verifyAccess(token);
    const { rows } = await query(
      'SELECT id, org_id, email, name, role, github_login, avatar_url FROM users WHERE id = $1',
      [decoded.sub]
    );
    if (!rows[0]) throw httpError(401, 'user_not_found');
    req.user = rows[0];
    next();
  } catch (err) {
    if (err.status) return next(err);
    next(httpError(401, 'invalid_token'));
  }
}

export function requireRole(...roles) {
  return (req, _res, next) => {
    if (!req.user) return next(httpError(401, 'unauthenticated'));
    if (!roles.includes(req.user.role)) return next(httpError(403, 'forbidden'));
    next();
  };
}
