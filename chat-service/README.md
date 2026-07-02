# Dublin Bikes Chat Service

Independent Python microservice for LLM chat (FastAPI + LangChain + DeepSeek). Spring Boot proxies `/api/chat/*` to this service; this service owns `message_store` reads/writes via LangChain `SQLChatMessageHistory`.

## Requirements

- Python 3.12+ (matches Dockerfile and Jenkins agent)
- PostgreSQL with `message_store` table (created by backend Flyway `V1__baseline.sql`)
- DeepSeek API key (`DEEPSEEK_API_KEY`)

## Local setup

```bash
cd chat-service
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env        # set CHAT_DB_URL and DEEPSEEK_API_KEY
uvicorn main:app --host 0.0.0.0 --port 8002
```

Health check (works without API keys):

```bash
curl http://localhost:8002/health
```

## HTTP API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness; `configured` reflects env |
| POST | `/chat` | Sync chat |
| POST | `/chat/stream` | SSE stream (`data: {"content":"..."}` then `data: [DONE]`) |
| POST | `/chat/title` | Generate session title |
| GET | `/sessions/{session_id}/messages` | Message history |

Spring default upstream: `http://localhost:8002` (`app.chat-service.base-url` in backend config).

## Trust boundaries

This service **does not** validate that `user_id` owns `session_id`. Spring Boot is expected to authenticate the caller (JWT), enforce `sessions` table ACL, and only forward requests for sessions that belong to that user. Treat direct calls to this service (bypassing Spring) as trusted-network only.

Before writing to `message_store`, `POST /chat` and `POST /chat/stream` upsert a row in `sessions` (id + `user_id`) so the Flyway V2 FK is satisfied. **`user_id` must reference an existing `users.id`** when testing directly (e.g. Postman). See [`docs/api/README.md`](../docs/api/README.md).

## Tests

```bash
cd chat-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest
```

## Docker

```bash
docker build -t dublin-bikes-chat-service:local .
docker run --rm -p 8002:8002 --env-file .env dublin-bikes-chat-service:local
```

## CI/CD

See [Jenkinsfile](./Jenkinsfile). Jenkins credential: `dublin-bikes-chat-service.env` (not committed).

Production deploy joins `dublin-bikes-network` only (no host port mapping). Spring reaches this service at `http://dublin-bikes-chat-service:8002` from inside Docker; do not expose `:8002` on the public host without a firewall in front.
