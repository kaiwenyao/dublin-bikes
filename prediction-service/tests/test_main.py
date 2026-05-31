"""Unit tests for prediction-service (model loading mocked; no .pkl required)."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from main import HealthReply, ModelBundle, PredictReply, _bundle, app

client = TestClient(app)
client_no_raise = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def _clear_bundle_cache():
    _bundle.cache_clear()
    yield
    _bundle.cache_clear()


def _fake_bundle(predictions=None) -> ModelBundle:
    estimator = MagicMock()
    estimator.predict.return_value = predictions if predictions is not None else [12.3, 7.8]
    return ModelBundle(
        estimator=estimator,
        features=("station_id", "capacity", "hour", "day_of_week"),
    )


def test_health_reports_unconfigured_when_model_missing():
    with patch("main._bundle", return_value=None):
        response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["configured"] is False
    assert body["features"] is None
    HealthReply.model_validate(body)


def test_health_reports_configured_with_feature_list():
    with patch("main._bundle", return_value=_fake_bundle()):
        response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["configured"] is True
    assert body["features"] == ["station_id", "capacity", "hour", "day_of_week"]


def test_predict_returns_503_when_model_missing():
    with patch("main._bundle", return_value=None):
        response = client.post("/predict", json={"rows": [{"station_id": 1}]})
    assert response.status_code == 503


def test_predict_returns_400_when_feature_column_missing():
    with patch("main._bundle", return_value=_fake_bundle()):
        response = client.post(
            "/predict",
            json={"rows": [{"station_id": 1, "capacity": 30}]},
        )
    assert response.status_code == 400
    assert "Missing required feature columns" in response.json()["detail"]


def test_predict_reorders_columns_and_rounds_predictions():
    bundle = _fake_bundle(predictions=[12.3, 7.8])
    with patch("main._bundle", return_value=bundle):
        response = client.post(
            "/predict",
            json={
                "rows": [
                    {"day_of_week": 0, "hour": 11, "capacity": 30, "station_id": 42},
                    {"day_of_week": 1, "hour": 12, "capacity": 30, "station_id": 42},
                ]
            },
        )
    assert response.status_code == 200
    body = response.json()
    assert body == {"predictions": [12, 8]}
    PredictReply.model_validate(body)

    df_arg = bundle.estimator.predict.call_args[0][0]
    assert list(df_arg.columns) == list(bundle.features)


def test_predict_rejects_empty_rows():
    response = client.post("/predict", json={"rows": []})
    assert response.status_code == 422


def test_predict_returns_500_when_estimator_raises():
    bundle = _fake_bundle()
    bundle.estimator.predict.side_effect = RuntimeError("model corrupted")
    with patch("main._bundle", return_value=bundle):
        response = client_no_raise.post(
            "/predict",
            json={
                "rows": [
                    {"station_id": 1, "capacity": 30, "hour": 11, "day_of_week": 0}
                ]
            },
        )
    assert response.status_code == 500
