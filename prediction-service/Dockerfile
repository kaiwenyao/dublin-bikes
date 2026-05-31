FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=UTC \
    MODEL_DIR=/app/machine_learning \
    HF_MODEL_REPO=ucdse/bike_availability_model

COPY requirements.txt .
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r requirements.txt

COPY main.py entrypoint.sh ./
COPY machine_learning ./machine_learning
RUN chmod +x entrypoint.sh

RUN useradd -ms /bin/bash appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8001

ENTRYPOINT ["./entrypoint.sh"]
