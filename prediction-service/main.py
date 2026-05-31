"""Dublin Bikes prediction-service: scikit-learn model serving over FastAPI."""

from __future__ import annotations

import logging
import pickle  # nosec B403 - trusted bundled artifact loaded at startup
from contextlib import asynccontextmanager
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        protected_namespaces=(),
    )

    model_dir: str = Field(default="machine_learning", validation_alias="MODEL_DIR")
    model_filename: str = Field(
        default="bike_availability_model.pkl",
        validation_alias="MODEL_FILENAME",
    )
    features_filename: str = Field(
        default="model_features.pkl",
        validation_alias="FEATURES_FILENAME",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()


@dataclass(frozen=True)
class ModelBundle:
    estimator: Any
    features: tuple[str, ...]


class PredictRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    rows: list[dict[str, Any]] = Field(..., min_length=1)


class PredictReply(BaseModel):
    predictions: list[int]


class HealthReply(BaseModel):
    status: str
    configured: bool
    features: list[str] | None = None


@lru_cache
def _bundle() -> ModelBundle | None:
    """Load the pickled sklearn estimator + feature list once. Returns None if files missing."""
    settings = get_settings()
    base = Path(settings.model_dir)
    model_path = base / settings.model_filename
    features_path = base / settings.features_filename

    if not model_path.exists() or not features_path.exists():
        logger.warning(
            "model artifacts not found: %s / %s — /predict will return 503",
            model_path,
            features_path,
        )
        return None

    with model_path.open("rb") as fh:
        estimator = pickle.load(fh)  # nosec B301
    with features_path.open("rb") as fh:
        features = pickle.load(fh)  # nosec B301
    if not isinstance(features, (list, tuple)):
        features = list(features)
    return ModelBundle(estimator=estimator, features=tuple(features))


def _require_bundle() -> ModelBundle:
    bundle = _bundle()
    if bundle is None:
        raise HTTPException(
            status_code=503,
            detail=(
                "Model artifacts not loaded. Place bike_availability_model.pkl and "
                "model_features.pkl under MODEL_DIR (default: ./machine_learning)."
            ),
        )
    return bundle


@asynccontextmanager
async def lifespan(_app: FastAPI):
    try:
        _bundle()  # warmup
    except Exception:
        logger.exception("model warmup failed")
    yield


app = FastAPI(
    title="dublin-bikes-prediction-service",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health", response_model=HealthReply)
def health() -> HealthReply:
    bundle = _bundle()
    return HealthReply(
        status="ok",
        configured=bundle is not None,
        features=list(bundle.features) if bundle else None,
    )


@app.post("/predict", response_model=PredictReply)
def predict(req: PredictRequest) -> PredictReply:
    bundle = _require_bundle()
    try:
        df = pd.DataFrame(req.rows)
        missing = [c for c in bundle.features if c not in df.columns]
        if missing:
            raise HTTPException(
                status_code=400,
                detail=f"Missing required feature columns: {missing}",
            )
        df = df[list(bundle.features)]
        raw = bundle.estimator.predict(df)
    except HTTPException:
        raise
    except Exception:
        logger.exception("predict failed; rows=%d", len(req.rows))
        raise HTTPException(status_code=500, detail="prediction failed")
    return PredictReply(predictions=[int(round(float(x))) for x in raw])
