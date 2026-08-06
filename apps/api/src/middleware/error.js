import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';

export function errorHandler(err, req, res, _next) {
  if (err && err.status && err.expose) {
    return res.status(err.status).json({ error: err.message });
  }
  logger.error({
    err: { message: err?.message, name: err?.name, stack: err?.stack },
    method: req.method,
    path: req.path,
    userId: req.user?.id || null
  }, 'unhandled_error');

  const body = { error: 'internal_error' };
  if (!config.isProd) {
    body.message = err?.message;
    body.stack = err?.stack;
  }
  res.status(500).json(body);
}

export function httpError(status, message) {
  const e = new Error(message);
  e.status = status;
  e.expose = true;
  return e;
}

// Global process handlers so a stray rejection kills the request, not the app,
// and is logged in a structured way.
export function installProcessGuards() {
  process.on('unhandledRejection', reason => {
    logger.error({ err: reason instanceof Error ? { message: reason.message, stack: reason.stack } : reason }, 'unhandled_rejection');
  });
  process.on('uncaughtException', err => {
    logger.fatal({ err: { message: err.message, stack: err.stack } }, 'uncaught_exception');
    // Give the logger a tick to flush, then exit — supervisor should restart us.
    setTimeout(() => process.exit(1), 100);
  });
}
