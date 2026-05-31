# Dublin Bikes Prediction Service

Independent Python microservice for hourly bike-availability prediction (FastAPI + scikit-learn). Spring Boot calls this service to fulfil `GET /api/stations/{number}/prediction`.

Reference Flask implementation: [`ucdse/flask-app/app/services/prediction_service.py`](https://github.com/ucdse/flask-app/blob/main/app/services/prediction_service.py). Same feature schema; the Flask app's Jenkins "Download ML Model" stage is replaced here by **container-start download** so the model can be rotated without rebuilding the image.

## Model artifacts

Two pickled sklearn files are required:

- `bike_availability_model.pkl` — the regression estimator
- `model_features.pkl` — the ordered feature column list

They are **not committed** (`.gitignore`). At container start, `entrypoint.sh` resolves them in this order:

1. Already present in `MODEL_DIR` (volume mount or baked-in) → use as-is.
2. `HF_TOKEN` is set → download from Hugging Face Hub repo `HF_MODEL_REPO` (default `ucdse/bike_availability_model`).
3. Neither → service starts anyway; `/health` reports `configured: false` and `/predict` returns `503` until artifacts appear.

## Requirements

- Python 3.12+ (matches Dockerfile)
- Hugging Face access token with read access to `ucdse/bike_availability_model` (production)
- Or local `.pkl` files in `./machine_learning/` (local dev)

## Local setup

```bash
cd prediction-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env       # set HF_TOKEN, or drop pkl files into ./machine_learning/

# Option A: rely on entrypoint download (mirrors prod)
./entrypoint.sh

# Option B: run uvicorn directly (skip download logic, assumes files already present)
uvicorn main:app --host 0.0.0.0 --port 8001
```

Health check:

```bash
curl http://localhost:8001/health
```

## HTTP API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness + `configured` flag + loaded feature list |
| POST | `/predict` | Batch predict from caller-supplied feature rows |

### `POST /predict`

The caller (Spring) builds one row per future hour and supplies them as `rows[]`. Each row must contain every column listed in `model_features.pkl`. The service reorders columns to match the training order, calls `model.predict(df)`, rounds to int, and returns. Spring clamps to `[0, station.bike_stands]`.

```json
POST /predict
{
  "rows": [
    {
      "station_id": 42, "capacity": 30, "lat": 53.34, "lon": -6.27,
      "hour": 11, "day": 20, "day_of_week": 0, "is_weekend": 0,
      "avg_temperature": 8.5, "avg_humidity": 78, "avg_pressure": 1015
    }
  ]
}

200 OK
{ "predictions": [18] }
```

`400` if any feature column is missing. `503` if the model artifacts have not been loaded.

Spring default upstream: `http://localhost:8001` (`app.prediction-service.base-url` in backend config).

## Trust boundaries

This service has **no database access** and does not validate caller identity. Spring Boot authenticates the request (JWT), looks up the station + future `weather_forecast` rows, builds feature rows, and posts them here. Treat direct calls as trusted-network only.

## Tests

```bash
pytest -q
```

7 tests, all mock the model — no `.pkl` files or HF token required.

## Docker

```bash
# Build (model files NOT baked in; downloaded at container start)
docker build -t dublin-bikes-prediction-service:local .

# Run with HF download
docker run --rm -p 8001:8001 \
  -e HF_TOKEN=hf_xxx \
  dublin-bikes-prediction-service:local

# Run with locally provided pkl files (volume mount)
docker run --rm -p 8001:8001 \
  -v $(pwd)/machine_learning:/app/machine_learning \
  dublin-bikes-prediction-service:local
```

## CI/CD

See [Jenkinsfile](./Jenkinsfile). Jenkins credentials:

- `huggingface-token` (Secret text) — Hugging Face Hub access token. The Deploy stage injects it as `-e HF_TOKEN=...` so the container's entrypoint can pull `bike_availability_model.pkl` and `model_features.pkl` on start. Same credential ID as the Flask project's "Download ML Model" stage; reuse the same token.
- `dublin-bikes-prediction-service.env` (Secret file) — runtime env file (mounted to `/opt/dublin-bikes-prediction-service/.env` on the remote server). Holds optional overrides such as `HF_MODEL_REPO`, `MODEL_DIR`. **`HF_TOKEN` does not go in here** — it comes from the dedicated credential above.

Production deploy joins `dublin-bikes-network` only (no host port mapping). Spring reaches this service at `http://dublin-bikes-prediction-service:8001` from inside Docker.
