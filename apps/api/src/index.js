import express from 'express';
import cors from 'cors';
import cookieParser from 'cookie-parser';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import http from 'node:http';
import { Server as IOServer } from 'socket.io';

import { config } from './utils/env.js';
import { logger, requestLogger } from './utils/logger.js';
import { authRoutes } from './routes/auth.js';
import { meRoutes } from './routes/me.js';
import { prRoutes } from './routes/prs.js';
import { reviewerRoutes } from './routes/reviewers.js';
import { analyticsRoutes } from './routes/analytics.js';
import { repoRoutes } from './routes/repos.js';
import { rulesRoutes } from './routes/rules.js';
import { incidentRoutes } from './routes/incidents.js';
import { settingsRoutes } from './routes/settings.js';
import { webhookRoutes } from './webhooks/github.js';
import { attachIO } from './realtime/io.js';
import { errorHandler, installProcessGuards, httpError } from './middleware/error.js';
import { csrfProtection } from './middleware/csrf.js';
import { healthCheck, pool } from './db/pool.js';
import { verifyAccess } from './middleware/auth.js';

installProcessGuards();

const app = express();

// If the app runs behind a proxy in prod, trust its X-Forwarded-For so rate
// limiting and secure-cookie detection work correctly.
if (config.trustProxy) app.set('trust proxy', 1);

// Security headers. We don't serve HTML from this service, so a strict CSP is
// simple — disable references we don't need.
app.use(helmet({
  crossOriginResourcePolicy: { policy: 'same-site' },
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'none'"],
      connectSrc: ["'self'"],
      frameAncestors: ["'none'"]
    }
  }
}));

// Strict CORS. Origins are opt-in via ALLOWED_ORIGINS (comma-separated); by
// default we allow only the WEB_ORIGIN. Never allows a wildcard.
const allowedOrigins = new Set(config.allowedOrigins);
app.use(cors({
  origin(origin, cb) {
    if (!origin) return cb(null, true);           // curl, server-to-server, same-origin
    if (allowedOrigins.has(origin)) return cb(null, true);
    logger.warn({ origin }, 'cors_rejected');
    cb(new Error('cors_not_allowed'));
  },
  credentials: true,
  methods: ['GET', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-CSRF-Token'],
  maxAge: 600
}));

app.use(cookieParser());
app.use(requestLogger());

// Webhook endpoint — raw body needed for HMAC verification. Mounted BEFORE
// the JSON parser and outside CSRF (protected by signature verification).
app.use(
  '/webhooks/github',
  express.raw({ type: '*/*', limit: '5mb' }),
  webhookRoutes
);

app.use(express.json({ limit: '1mb' }));

// Health endpoints.
// /health/live  — liveness: process is up. Cheap, no dependencies.
// /health/ready — readiness: dependencies (DB) reachable. Used by orchestrators.
// /health       — legacy alias, kept for existing monitors.
app.get('/health/live', (_req, res) => res.json({ ok: true, status: 'live' }));
app.get('/health/ready', async (_req, res) => {
  try {
    const dbOk = await healthCheck();
    res.status(dbOk ? 200 : 503).json({ ok: dbOk, status: dbOk ? 'ready' : 'db_unreachable' });
  } catch (err) {
    logger.error({ err: { message: err.message } }, 'health_check_failed');
    res.status(503).json({ ok: false, status: 'db_unreachable' });
  }
});
app.get('/health', (_req, res) => res.json({ ok: true, service: 'meridian-api' }));

// Rate limiting.
// - Auth endpoints (login / OAuth): tight window, low cap, keyed by IP.
// - Everything else: generous per-minute cap.
const authLimiter = rateLimit({
  windowMs: config.rateLimit.authWindowMs,
  max: config.rateLimit.authMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate_limited' }
});
const apiLimiter = rateLimit({
  windowMs: config.rateLimit.apiWindowMs,
  max: config.rateLimit.apiMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate_limited' }
});

app.use('/auth', authLimiter);

// CSRF for all mutating requests. Login is allowed to run without a prior
// token (double-submit is issued on the response), so we place the middleware
// AFTER the auth limiter but the CSRF guard itself is method-based (safe for
// login POST because the browser is the origin that also owns the cookie).
app.use(csrfProtection);

app.use('/auth', authRoutes);
app.use(apiLimiter);
app.use('/me', meRoutes);
app.use('/prs', prRoutes);
app.use('/reviewers', reviewerRoutes);
app.use('/analytics', analyticsRoutes);
app.use('/repos', repoRoutes);
app.use('/rules', rulesRoutes);
app.use('/incidents', incidentRoutes);
app.use('/settings', settingsRoutes);

// Not-found fallthrough.
app.use((_req, _res, next) => next(httpError(404, 'not_found')));

app.use(errorHandler);

const server = http.createServer(app);
const io = new IOServer(server, {
  cors: {
    origin: (origin, cb) =>
      !origin || allowedOrigins.has(origin) ? cb(null, true) : cb(new Error('cors_not_allowed')),
    credentials: true
  },
  path: '/live'
});
attachIO(io, { verifyAccess });

const shutdown = async signal => {
  logger.info({ signal }, 'shutting_down');
  server.close(async () => {
    try { await pool.end(); } catch (e) { logger.error({ err: e }, 'pool_close_failed'); }
    process.exit(0);
  });
  // Force exit after 10s if graceful shutdown hangs.
  setTimeout(() => process.exit(1), 10_000).unref();
};
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

server.listen(config.port, () => {
  logger.info({ port: config.port, env: config.env }, 'api_listening');
});
