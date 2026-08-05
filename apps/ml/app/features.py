"""
Feature vector definition. Kept in one place so the API and model agree.
Everything is either numeric or 0/1 — no strings — so it's trivial to
serialize and feed into LightGBM.
"""
FEATURE_ORDER = [
    "additions",
    "deletions",
    "changed_files",
    "commits_count",
    "touches_auth",
    "touches_billing",
    "touches_infra",
    "touches_secret",
    "hot_file_overlap",
    "commit_msg_quality",
    "author_pr_count",
    "author_revert_rate",
    "opened_hour",
    "opened_dow",
]

FEATURE_LABELS = {
    "additions":           "Lines added",
    "deletions":           "Lines removed",
    "changed_files":       "Files changed",
    "commits_count":       "Commits in PR",
    "touches_auth":        "Touches auth code",
    "touches_billing":     "Touches billing code",
    "touches_infra":       "Touches infra / IaC",
    "touches_secret":      "Touches secrets / env",
    "hot_file_overlap":    "Overlaps hot files",
    "commit_msg_quality":  "Commit message quality",
    "author_pr_count":     "Author PR history",
    "author_revert_rate":  "Author revert rate",
    "opened_hour":         "Hour opened (UTC)",
    "opened_dow":          "Day opened (0=Sun)",
}


def to_vector(f: dict) -> list[float]:
    return [float(f.get(k, 0) or 0) for k in FEATURE_ORDER]
