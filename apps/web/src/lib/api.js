// In dev, Vite proxies /api → localhost:4000. In prod (Render), set
// VITE_API_BASE at build time to the API service origin (e.g.
// https://meridian-api.onrender.com). All requests use credentials:'include',
// so the API must send CORS with credentials + list this origin in
// ALLOWED_ORIGINS. Falls back to /api for the dev proxy path.
export const API_BASE = (import.meta.env?.VITE_API_BASE || '/api').replace(/\/$/, '');

// CSRF token handling.
//
// The API sets a readable `mrd_csrf` cookie AND returns the same value in an
// `X-CSRF-Token` response header. Which one we can actually use depends on the
// deployment:
//
//   - Same-origin (local dev, via the Vite proxy): the cookie is readable, and
//     it survives a page reload, so it is the better source.
//   - Split-origin (production: meridian-web and meridian-api are different
//     *.onrender.com subdomains, and onrender.com is on the Public Suffix List,
//     so they are different *sites*): the cookie belongs to the API's domain and
//     `document.cookie` here can never see it. The response header is the only
//     way to read it.
//
// So: prefer the cookie, fall back to the header value cached from the last
// response. The cache is memory-only and repopulates on the first request after
// a reload, which is always a GET (the app calls /me on mount).
let cachedCsrfToken = null;

function cookieCsrfToken() {
  if (typeof document === 'undefined') return null;
  const m = document.cookie.match(/(?:^|;\s*)mrd_csrf=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

function csrfToken() {
  return cookieCsrfToken() || cachedCsrfToken;
}

async function req(path, opts = {}) {
  const method = (opts.method || 'GET').toUpperCase();
  const headers = { 'content-type': 'application/json', ...(opts.headers || {}) };
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = csrfToken();
    if (token) headers['x-csrf-token'] = token;
  }
  const res = await fetch(API_BASE + path, {
    credentials: 'include',
    headers,
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });

  // Cache the token the server just handed us, so the next mutation has one even
  // when the cookie is invisible to this origin.
  const headerToken = res.headers.get('X-CSRF-Token');
  if (headerToken) cachedCsrfToken = headerToken;

  if (res.status === 401) throw Object.assign(new Error('unauthorized'), { status: 401 });
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('application/json') ? await res.json() : await res.text();
  if (!res.ok) throw Object.assign(new Error(data?.error || 'request_failed'), { status: res.status, data });
  return data;
}

export const api = {
  me: () => req('/me'),
  login: (body) => req('/auth/login', { method: 'POST', body }),
  logout: () => req('/auth/logout', { method: 'POST' }),
  refresh: () => req('/auth/refresh', { method: 'POST' }),
  overview: () => req('/analytics/overview'),
  prs: (params = {}) => req('/prs' + qs(params)),
  pr: (id) => req(`/prs/${id}`),
  prOutcome: (id, body) => req(`/prs/${id}/outcome`, { method: 'POST', body }),
  reviewers: () => req('/reviewers'),
  suggest: (prId) => req('/reviewers/suggest?pr_id=' + encodeURIComponent(prId)),
  analytics: {
    size: () => req('/analytics/pr-size-trend'),
    cycle: () => req('/analytics/cycle-time'),
    heatmap: () => req('/analytics/merge-heatmap'),
    reverts: () => req('/analytics/revert-rate'),
    authors: () => req('/analytics/author-trends')
  },
  repos: () => req('/repos'),
  updateRepo: (id, body) => req(`/repos/${id}`, { method: 'PATCH', body }),
  rules: () => req('/rules'),
  createRule: (body) => req('/rules', { method: 'POST', body }),
  updateRule: (id, body) => req(`/rules/${id}`, { method: 'PATCH', body }),
  deleteRule: (id) => req(`/rules/${id}`, { method: 'DELETE' }),
  incidents: () => req('/incidents'),
  createIncident: (body) => req('/incidents', { method: 'POST', body }),
  settings: () => req('/settings'),
  updateSettings: (body) => req('/settings', { method: 'PATCH', body })
};

function qs(params) {
  const s = Object.entries(params).filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return s ? '?' + s : '';
}
