"""
Train a LightGBM classifier on synthetic PR data.

We don't have real revert-labeled data yet, so we generate synthetic labels
via a hand-crafted risk function with noise. This gives the model something
to fit to, so at serving time we return calibrated probabilities and true
per-prediction feature contributions rather than heuristic constants.

Later, once pr_outcomes has real reverted/hotfixed data, replace the
_synthesize_labels() call with a query loader.
"""
from __future__ import annotations
import numpy as np
import lightgbm as lgb
import pickle
from pathlib import Path
from .features import FEATURE_ORDER

RNG = np.random.default_rng(42)
MODEL_PATH = Path(__file__).parent / "model.pkl"
MODEL_VERSION = "lgbm-synth-0.1.0"


def _synthesize(n: int = 6000):
    X = np.zeros((n, len(FEATURE_ORDER)))
    for i in range(n):
        X[i] = [
            RNG.gamma(2.0, 60),                       # additions
            RNG.gamma(2.0, 30),                       # deletions
            RNG.gamma(1.5, 3),                        # changed_files
            RNG.integers(1, 20),                      # commits_count
            RNG.binomial(1, 0.12),                    # touches_auth
            RNG.binomial(1, 0.10),                    # touches_billing
            RNG.binomial(1, 0.08),                    # touches_infra
            RNG.binomial(1, 0.04),                    # touches_secret
            RNG.beta(1.5, 8),                         # hot_file_overlap
            RNG.beta(3, 2),                           # commit_msg_quality
            RNG.integers(1, 300),                     # author_pr_count
            RNG.beta(1, 30),                          # author_revert_rate
            RNG.integers(0, 24),                      # opened_hour
            RNG.integers(0, 7),                       # opened_dow
        ]
    # ground-truth risk function (unknown to the model)
    risk = (
        0.0005 * X[:, 0]                # additions
      + 0.0008 * X[:, 1]                # deletions
      + 0.02   * X[:, 2]                # changed_files
      + 0.30   * X[:, 4]                # auth
      + 0.35   * X[:, 5]                # billing
      + 0.32   * X[:, 6]                # infra
      + 0.40   * X[:, 7]                # secret
      + 1.20   * X[:, 8]                # hot_file_overlap
      - 0.25   * X[:, 9]                # good commit msg reduces risk
      + 0.80   * X[:, 11]               # author revert rate
      + 0.08   * ((X[:, 12] >= 22) | (X[:, 12] <= 4))   # late-night
      + 0.10   * (X[:, 13] == 5)         # friday
    )
    prob = 1 / (1 + np.exp(-(risk - 1.0)))
    y = RNG.binomial(1, np.clip(prob, 0.01, 0.99))
    return X, y


def main():
    X, y = _synthesize()
    model = lgb.LGBMClassifier(
        n_estimators=250, learning_rate=0.05, max_depth=6,
        num_leaves=31, min_child_samples=25, random_state=42,
        verbosity=-1,
    )
    model.fit(X, y)
    with open(MODEL_PATH, "wb") as f:
        pickle.dump({"model": model, "version": MODEL_VERSION,
                     "features": FEATURE_ORDER}, f)
    print(f"[train] wrote {MODEL_PATH} version={MODEL_VERSION}")


if __name__ == "__main__":
    main()
