# Model artifacts

Two trained sklearn pickles are required at runtime:

- `bike_availability_model.pkl` — the regression estimator
- `model_features.pkl` — the ordered feature column list used during training

Both files are gitignored. The container's `entrypoint.sh` resolves them at startup:

1. If already present here (volume mount or local copy) → use as-is.
2. Else if `HF_TOKEN` is set → download from `huggingface.co/HF_MODEL_REPO` (default `ucdse/bike_availability_model`).
3. Else → service starts but `/predict` returns 503.

For local dev you can drop the files here directly and skip the HF token.

The feature schema must stay in sync with the Spring-side feature-row builder
(`backend/src/main/java/dev/kaiwen/bikes/service/PredictionService.java`,
`backend/devplan/04-modules.md` §6.4). Concretely the training pipeline must accept these columns:

```
station_id, capacity, lat, lon,
hour, day, day_of_week, is_weekend,
avg_temperature, avg_humidity, avg_pressure
```

with `day_of_week` using Monday=0 .. Sunday=6 (Python `datetime.weekday()` semantics).
