# Dublin Bikes Prediction Service

Independent Python microservice for hourly bike-availability prediction (FastAPI + scikit-learn). Spring Boot calls this service to fulfil `GET /api/stations/{number}/prediction`.

Reference Flask implementation: [`ucdse/flask-app/app/services/prediction_service.py`](https://github.com/ucdse/flask-app/blob/main/app/services/prediction_service.py). This service keeps the same model and feature schema; feature-row assembly moves to the Spring side (see `backend/devplan/04-modules.md` §6.4).

## Requirements

- Python 3.12+ (matches Dockerfile and Jenkins agent)
- Pickled sklearn artifacts:
  - `machine_learning/bike_availability_model.pkl`
  - `machine_learning/model_features.pkl`

These files are **not committed** (see `.gitignore`). Drop them in before running locally; bake them into the image at CI time.

## Local setup

```bash
cd prediction-service
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env

# put the .pkl files into ./machine_learning/ first
uvicorn main:app --host 0.0.0.0 --port 8001
```

Health check (reports `configured: false` if model files are missing):

```bash
curl http://localhost:8001/health
```

## HTTP API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness + `configured` flag + loaded feature list |
| POST | `/predict` | Batch predict from caller-supplied feature rows |

### `POST /predict`

The caller (Spring) builds one row per future hour and supplies them as `rows[]`. Each row must contain every column listed in `model_features.pkl`. The service reorders columns to match the training order, calls `model.predict(df)`, rounds, and returns integers. Spring clamps to `[0, station.bike_stands]`.

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

This service has **no database access** and **does not** validate caller identity. Spring Boot authenticates the request (JWT), looks up the station + future `weather_forecast` rows, builds feature rows, and posts them here. Treat direct calls to this service as trusted-network only.

## Tests

```bash
cd prediction-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest
```

## Docker

```bash
# .pkl files must live in ./machine_learning/ before build (they get COPYed in)
docker build -t dublin-bikes-prediction-service:local .
docker run --rm -p 8001:8001 --env-file .env dublin-bikes-prediction-service:local
```

## CI/CD

See [Jenkinsfile](./Jenkinsfile). Jenkins credentials:

- `dublin-bikes-prediction-service.env` — runtime env file (mounted to remote `/opt/dublin-bikes-prediction-service/.env`).
- The `.pkl` artifacts must be present in the Jenkins workspace at build time. Either commit them to a private artifacts repo or have a pre-build step fetch them from object storage; do **not** check large pickles into the main repo.

Production deploy joins `dublin-bikes-network` only (no host port mapping). Spring reaches this service at `http://dublin-bikes-prediction-service:8001` from inside Docker.
