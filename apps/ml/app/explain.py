"""
Lightweight explainability. LightGBM exposes per-tree leaf paths; the fastest
per-prediction attribution is `predict(pred_contrib=True)` which returns SHAP
values directly. We take absolute contributions, keep sign as direction,
and return the top-N.
"""
from __future__ import annotations
import numpy as np
from .features import FEATURE_ORDER, FEATURE_LABELS


def contributions(model, x_row: np.ndarray, top_k: int = 8) -> list[dict]:
    contribs = model.booster_.predict(x_row.reshape(1, -1), pred_contrib=True)[0]
    # last element is the bias term
    weights = contribs[:-1]
    order = np.argsort(-np.abs(weights))[:top_k]
    out = []
    for idx in order:
        w = float(weights[idx])
        if abs(w) < 1e-4:
            continue
        feat = FEATURE_ORDER[idx]
        out.append({
            "feature": feat,
            "label": FEATURE_LABELS.get(feat, feat),
            "value": float(x_row[idx]),
            "weight": round(abs(w), 4),
            "direction": "up" if w > 0 else "down",
        })
    return out
