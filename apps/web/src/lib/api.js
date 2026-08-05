const BASE = '/api';

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    credentials: 'include',
    headers: { 'content-type': 'application/json', ...(opts.headers || {}) },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined
  });
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
  overview: () => req('/analytics/overview'),
  prs: (params = {}) => req('/prs' + qs(params)),
  pr: (id) => req(`/prs/${id}`),
  prOutcome: (id, body) => req(`/prs/${id}/outcome`, { method: 'POST', body }),
  reviewers: () => req('/reviewers'),
  suggest: (prId) => req('/reviewers/suggest?pr_id=' + prId),
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
