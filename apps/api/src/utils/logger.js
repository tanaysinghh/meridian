import pino from 'pino';
import { config } from './env.js';

const redactPaths = [
  'req.headers.authorization',
  'req.headers.cookie',
  'req.headers["x-hub-signature-256"]',
  '*.password',
  '*.password_hash',
  '*.refresh_token',
  '*.access_token',
  '*.token',
  '*.api_key',
  '*.jwt',
  '*.secret',
  '*.client_secret',
  'user.password_hash',
  'body.password'
];

export const logger = pino({
  level: config.logLevel,
  redact: { paths: redactPaths, censor: '[REDACTED]' },
  base: { service: 'meridian-api', env: config.env },
  transport: config.isDev
    ? { target: 'pino-pretty', options: { colorize: true, translateTime: 'SYS:HH:MM:ss' } }
    : undefined
});

// Simple express middleware — logs one line per request without leaking headers/body.
export function requestLogger() {
  return (req, res, next) => {
    const start = process.hrtime.bigint();
    res.on('finish', () => {
      const ms = Number(process.hrtime.bigint() - start) / 1e6;
      const line = {
        method: req.method,
        path: req.path,
        status: res.statusCode,
        ms: Math.round(ms),
        userId: req.user?.id || null
      };
      if (res.statusCode >= 500) logger.error(line, 'request');
      else if (res.statusCode >= 400) logger.warn(line, 'request');
      else logger.info(line, 'request');
    });
    next();
  };
}
