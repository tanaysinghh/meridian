from __future__ import annotations
import pickle, os, numpy as np
from pathlib import Path
from fastapi import FastAPI
from pydantic import BaseModel, Field
from .features import to_vector, FEATURE_ORDER
from .explain import contributions

MODEL_PATH = Path(__file__).parent / "model.pkl"
app = FastAPI(title="Meridian ML")

_state = {"model": None, "version": None}


def _load():
    if _state["model"] is not None:
        return
    if not MODEL_PATH.exists():
        # Auto-train on first boot so dev doesn't need an extra step.
        from . import train
        train.main()
    with open(MODEL_PATH, "rb") as f:
        payload = pickle.load(f)
    _state["model"] = payload["model"]
    _state["version"] = payload["version"]


class ScoreRequest(BaseModel):
    features: dict = Field(default_factory=dict)


def _tier(score: float) -> str:
    if score >= 0.85: return "critical"
    if score >= 0.65: return "high"
    if score >= 0.35: return "medium"
    return "low"


def _confidence(features: dict) -> str:
    author_prs = features.get("author_pr_count", 0) or 0
    if author_prs < 5:
        return "low"
    if author_prs < 20:
        return "medium"
    return "high"


@app.get("/health")
def health():
    _load()
    return {"ok": True, "model_version": _state["version"]}


@app.post("/score")
def score(req: ScoreRequest):
    _load()
    x = np.array(to_vector(req.features))
    prob = float(_state["model"].predict_proba(x.reshape(1, -1))[0, 1])
    contrib = contributions(_state["model"], x, top_k=8)
    return {
        "score": round(prob, 3),
        "tier": _tier(prob),
        "confidence": _confidence(req.features),
        "model_version": _state["version"],
        "contributions": contrib,
    }
