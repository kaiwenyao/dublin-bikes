#!/bin/sh
# Container entrypoint: ensure model artifacts are present, then start uvicorn.
#
# Resolution order for the .pkl files:
#   1. Already present under $MODEL_DIR (volume mount or baked-in) — use them.
#   2. HF_TOKEN set — download from Hugging Face Hub repo $HF_MODEL_REPO
#      (default: ucdse/bike_availability_model). Mirrors the Flask project's
#      Jenkins "Download ML Model" stage, except executed at container start so
#      the model can be updated without rebuilding the image.
#   3. Neither — start anyway; /health reports `configured: false` and /predict
#      returns 503 until artifacts appear.

set -e

MODEL_DIR="${MODEL_DIR:-/app/machine_learning}"
HF_MODEL_REPO="${HF_MODEL_REPO:-ucdse/bike_availability_model}"
MODEL_FILENAME="${MODEL_FILENAME:-bike_availability_model.pkl}"
FEATURES_FILENAME="${FEATURES_FILENAME:-model_features.pkl}"
MODEL_FILE="${MODEL_DIR}/${MODEL_FILENAME}"
FEATURES_FILE="${MODEL_DIR}/${FEATURES_FILENAME}"

mkdir -p "${MODEL_DIR}"

if [ -f "${MODEL_FILE}" ] && [ -f "${FEATURES_FILE}" ]; then
    echo "[entrypoint] model artifacts already present in ${MODEL_DIR}, skipping download"
elif [ -z "${HF_TOKEN}" ]; then
    echo "[entrypoint] WARNING: HF_TOKEN is not set and model files are missing." >&2
    echo "[entrypoint] /predict will return 503 until artifacts are provided." >&2
else
    echo "[entrypoint] downloading model artifacts from huggingface.co/${HF_MODEL_REPO}..."
    export HF_MODEL_REPO HF_TOKEN MODEL_DIR MODEL_FILENAME FEATURES_FILENAME
    python - <<'PY'
import os
import shutil

from huggingface_hub import hf_hub_download

repo_id = os.environ["HF_MODEL_REPO"]
token = os.environ["HF_TOKEN"]
target_dir = os.environ["MODEL_DIR"]
filenames = [os.environ["MODEL_FILENAME"], os.environ["FEATURES_FILENAME"]]

os.makedirs(target_dir, exist_ok=True)
for name in filenames:
    src = hf_hub_download(repo_id=repo_id, filename=name, token=token)
    dst = os.path.join(target_dir, name)
    shutil.copy(src, dst)
    print(f"[entrypoint]   ok: {name} -> {dst}", flush=True)

print("[entrypoint] model download complete", flush=True)
PY
fi

echo "[entrypoint] starting uvicorn on :8001"
exec uvicorn main:app --host 0.0.0.0 --port 8001
