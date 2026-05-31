# Model artifacts

Drop the trained sklearn pickles here before starting the service:

- `bike_availability_model.pkl` — the regression estimator
- `model_features.pkl` — the ordered feature column list used during training

Both files are gitignored. The Dockerfile copies this directory into the image; the Jenkins build pulls them from secure storage or expects them present in the workspace.

The feature schema must stay in sync with the Spring-side feature-row builder
(`backend/src/main/java/dev/kaiwen/bikes/service/PredictionService.java`,
`backend/devplan/04-modules.md` §6.4). Concretely the training pipeline must accept these columns:

```
station_id, capacity, lat, lon,
hour, day, day_of_week, is_weekend,
avg_temperature, avg_humidity, avg_pressure
```

with `day_of_week` using Monday=0 .. Sunday=6 (Python `datetime.weekday()` semantics).
