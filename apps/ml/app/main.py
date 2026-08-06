from __future__ import annotations
import logging
import os
import pickle
from pathlib import Path

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .explain import contributions
from .features import to_vector

MODEL_PATH = Path(__file__).parent / "model.pkl"

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format='{"time":"%(asctime)s","level":"%(levelname)s","msg":"%(message)s","logger":"%(name)s"}',
)
log = logging.getLogger("meridian.ml")

app = FastAPI(title="Meridian ML", docs_url=None, redoc_url=None, openapi_url=None)

# The ML service is internal — only the Meridian API should call it. Origins
# are opt-in via env; default is empty (no browser access).
_allowed = [o.strip() for o in os.environ.get("ALLOWED_ORIGINS", "").split(",") if o.strip()]
if _allowed:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_allowed,
        allow_credentials=False,
        allow_methods=["POST", "GET"],
        allow_headers=["Content-Type"],
    )

_state = {"model": None, "version": None}


def _load():
    if _state["model"] is not None:
        return
    if not MODEL_PATH.exists():
        # Auto-train on first boot so dev doesn't need an extra step. In prod,
        # bake the model into the image and skip this path.
        if os.environ.get("ENV") == "production" and os.environ.get("ALLOW_TRAIN_ON_BOOT") != "yes":
            raise RuntimeError("model.pkl missing and ALLOW_TRAIN_ON_BOOT is not set")
        from . import train  # noqa: WPS433
        train.main()
    with open(MODEL_PATH, "rb") as f:
        payload = pickle.load(f)
    _state["model"] = payload["model"]
    _state["version"] = payload["version"]


class ScoreRequest(BaseModel):
    # Bound the feature dict size to keep payloads sane.
    features: dict = Field(default_factory=dict, max_length=200)


def _tier(score: float) -> str:
    if score >= 0.85:
        return "critical"
    if score >= 0.65:
        return "high"
    if score >= 0.35:
        return "medium"
    return "low"


def _confidence(features: dict) -> str:
    author_prs = features.get("author_pr_count", 0) or 0
    if author_prs < 5:
        return "low"
    if author_prs < 20:
        return "medium"
    return "high"


# Liveness: process is up. Cheap, no model load.
@app.get("/health/live")
def health_live():
    return {"ok": True, "status": "live"}


# Readiness: model is loaded (or can be). Used by orchestrators.
@app.get("/health/ready")
def health_ready():
    try:
        _load()
        return {"ok": True, "status": "ready", "model_version": _state["version"]}
    except Exception as exc:
        log.error("readiness_failed: %s", exc)
        raise HTTPException(status_code=503, detail="model_unavailable")


# Kept for backwards compatibility with the API's earlier /health probe.
@app.get("/health")
def health():
    try:
        _load()
        return {"ok": True, "model_version": _state["version"]}
    except Exception as exc:
        log.error("health_failed: %s", exc)
        raise HTTPException(status_code=503, detail="model_unavailable")


@app.post("/score")
def score(req: ScoreRequest):
    try:
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
    except HTTPException:
        raise
    except Exception as exc:
        log.exception("score_failed")
        # Never leak stack traces to callers.
        raise HTTPException(status_code=500, detail="score_error")
