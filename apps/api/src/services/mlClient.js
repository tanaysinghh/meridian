import { request } from 'undici';
import { config } from '../utils/env.js';
import { logger } from '../utils/logger.js';

// If the ML service is unreachable, degrade to a rule-only score so webhook
// ingestion doesn't stall on ML being down.
export async function scorePR(features) {
  try {
    const { statusCode, body } = await request(`${config.mlServiceUrl}/score`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ features }),
      bodyTimeout: 5000
    });
    if (statusCode >= 400) throw new Error(`ml_${statusCode}`);
    return await body.json();
  } catch (err) {
    logger.warn({ err: err.message }, 'ml_fallback_scoring');
    return fallbackScore(features);
  }
}

function fallbackScore(f) {
  let s = 0.15;
  s += Math.min((f.additions || 0) / 1000, 0.35);
  s += Math.min((f.changed_files || 0) / 40, 0.20);
  if (f.touches_auth) s += 0.15;
  if (f.touches_billing) s += 0.15;
  if (f.touches_infra) s += 0.15;
  if (f.touches_secret) s += 0.15;
  if ((f.commit_msg_quality || 0.5) < 0.4) s += 0.05;
  s = Math.max(0.05, Math.min(0.98, s));
  const tier = s >= 0.85 ? 'critical' : s >= 0.65 ? 'high' : s >= 0.35 ? 'medium' : 'low';
  return {
    score: +s.toFixed(3),
    tier,
    confidence: 'low',
    model_version: 'fallback-heuristic-0.1',
    contributions: Object.entries(f).slice(0, 5).map(([feature, v]) => ({
      feature, weight: 0.1, direction: 'up', value: v
    }))
  };
}
