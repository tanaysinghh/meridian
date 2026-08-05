export function errorHandler(err, req, res, _next) {
  if (err && err.status && err.expose) {
    return res.status(err.status).json({ error: err.message });
  }
  console.error('[api] error', err);
  res.status(500).json({ error: 'internal_error' });
}

export function httpError(status, message) {
  const e = new Error(message);
  e.status = status;
  e.expose = true;
  return e;
}
