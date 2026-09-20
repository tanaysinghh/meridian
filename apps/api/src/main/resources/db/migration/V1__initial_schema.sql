-- Meridian schema, V1.
--
-- Direct translation of the pre-migration hand-rolled `src/db/schema.sql`.
-- `IF NOT EXISTS` is kept throughout so this version can be applied to a
-- database that was already provisioned by the old `node src/db/migrate.js`
-- script (paired with `spring.flyway.baseline-on-migrate=true`). Later
-- versions should use plain DDL — Flyway tracks what has run from here on.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS orgs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          TEXT NOT NULL,
  slug          TEXT NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id        UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  email         TEXT NOT NULL UNIQUE,
  name          TEXT,
  password_hash TEXT,                    -- null if github-only user
  github_login  TEXT UNIQUE,
  github_id     BIGINT UNIQUE,
  avatar_url    TEXT,
  role          TEXT NOT NULL DEFAULT 'developer'
                CHECK (role IN ('developer','team_lead','admin')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_token TEXT NOT NULL UNIQUE,
  expires_at    TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS repos (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  github_id           BIGINT UNIQUE,
  full_name           TEXT NOT NULL,      -- owner/repo
  default_branch      TEXT NOT NULL DEFAULT 'main',
  risk_threshold      NUMERIC(4,3) NOT NULL DEFAULT 0.60,  -- tier boundary tuning
  connected_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(org_id, full_name)
);

CREATE TABLE IF NOT EXISTS repo_rules (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id   UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
  name      TEXT NOT NULL,
  enabled   BOOLEAN NOT NULL DEFAULT true,
  -- predicate: {type, args...}. Types: path_glob, author_in, size_gt, touches_paths, regex_match
  predicate JSONB NOT NULL,
  -- action: {escalate_to: 'high'|'critical', reason: '...'}
  action    JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS pull_requests (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id           UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
  number            INTEGER NOT NULL,
  github_id         BIGINT UNIQUE,
  title             TEXT NOT NULL,
  body              TEXT,
  author_login      TEXT NOT NULL,
  author_avatar     TEXT,
  state             TEXT NOT NULL CHECK (state IN ('open','closed','merged')),
  draft             BOOLEAN NOT NULL DEFAULT false,
  base_ref          TEXT NOT NULL,
  head_ref          TEXT NOT NULL,
  additions         INTEGER NOT NULL DEFAULT 0,
  deletions         INTEGER NOT NULL DEFAULT 0,
  changed_files     INTEGER NOT NULL DEFAULT 0,
  commits_count     INTEGER NOT NULL DEFAULT 0,
  file_paths        TEXT[] NOT NULL DEFAULT '{}',
  labels            TEXT[] NOT NULL DEFAULT '{}',
  requested_reviewers TEXT[] NOT NULL DEFAULT '{}',
  url               TEXT,
  opened_at         TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  merged_at         TIMESTAMPTZ,
  closed_at         TIMESTAMPTZ,
  first_review_at   TIMESTAMPTZ,
  approved_at       TIMESTAMPTZ,
  UNIQUE(repo_id, number)
);

CREATE INDEX IF NOT EXISTS pr_repo_state_idx ON pull_requests(repo_id, state);
CREATE INDEX IF NOT EXISTS pr_author_idx ON pull_requests(author_login);
CREATE INDEX IF NOT EXISTS pr_updated_idx ON pull_requests(updated_at DESC);

CREATE TABLE IF NOT EXISTS pr_events (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pr_id        UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
  event_type   TEXT NOT NULL,   -- opened, synchronize, review_requested, review_submitted, comment, closed, merged
  actor_login  TEXT,
  payload      JSONB,
  occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS pr_events_pr_idx ON pr_events(pr_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS pr_risk_scores (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pr_id         UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
  score         NUMERIC(4,3) NOT NULL,     -- 0..1
  tier          TEXT NOT NULL CHECK (tier IN ('low','medium','high','critical')),
  confidence    TEXT NOT NULL CHECK (confidence IN ('low','medium','high')),
  features      JSONB NOT NULL,            -- input feature vector
  contributions JSONB NOT NULL,            -- [{feature, weight, direction}]
  rule_hits     JSONB NOT NULL DEFAULT '[]',
  model_version TEXT NOT NULL,
  scored_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS pr_scores_pr_idx ON pr_risk_scores(pr_id, scored_at DESC);

CREATE TABLE IF NOT EXISTS pr_outcomes (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pr_id        UUID NOT NULL UNIQUE REFERENCES pull_requests(id) ON DELETE CASCADE,
  reverted     BOOLEAN NOT NULL DEFAULT false,
  hotfixed     BOOLEAN NOT NULL DEFAULT false,
  caused_incident BOOLEAN NOT NULL DEFAULT false,
  outcome_notes TEXT,
  observed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS incidents (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id       UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  repo_id      UUID REFERENCES repos(id) ON DELETE SET NULL,
  related_pr_id UUID REFERENCES pull_requests(id) ON DELETE SET NULL,
  title        TEXT NOT NULL,
  severity     TEXT NOT NULL CHECK (severity IN ('sev1','sev2','sev3','sev4')),
  description  TEXT,
  reported_by  UUID REFERENCES users(id) ON DELETE SET NULL,
  occurred_at  TIMESTAMPTZ NOT NULL,
  resolved_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Derived file-hotness — recomputed periodically. Keyed by (repo, path).
CREATE TABLE IF NOT EXISTS file_hotness (
  repo_id       UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
  path          TEXT NOT NULL,
  change_count  INTEGER NOT NULL DEFAULT 0,
  revert_count  INTEGER NOT NULL DEFAULT 0,
  incident_count INTEGER NOT NULL DEFAULT 0,
  score         NUMERIC(4,3) NOT NULL DEFAULT 0,
  last_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (repo_id, path)
);

-- Lightweight CODEOWNERS inference from commit history.
CREATE TABLE IF NOT EXISTS file_ownership (
  repo_id       UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
  path_prefix   TEXT NOT NULL,
  owner_login   TEXT NOT NULL,
  commits       INTEGER NOT NULL DEFAULT 0,
  last_touched  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (repo_id, path_prefix, owner_login)
);

CREATE TABLE IF NOT EXISTS org_settings (
  org_id                    UUID PRIMARY KEY REFERENCES orgs(id) ON DELETE CASCADE,
  slack_webhook_url         TEXT,          -- overrides env when set
  digest_recipients         TEXT[] NOT NULL DEFAULT '{}',
  high_risk_sla_hours       INTEGER NOT NULL DEFAULT 8,
  auto_escalate             BOOLEAN NOT NULL DEFAULT true,
  notify_on_tiers           TEXT[] NOT NULL DEFAULT ARRAY['high','critical']
);
