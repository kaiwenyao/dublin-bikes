# Dublin Bikes Chat Service

Independent Python microservice for LLM chat (FastAPI + LangChain + Qwen). Spring Boot proxies `/api/chat/*` to this service; this service owns `message_store` reads/writes via LangChain `SQLChatMessageHistory`.

## Requirements

- Python 3.11+
- PostgreSQL with `message_store` table (created by backend Flyway `V1__baseline.sql`)
- Alibaba Cloud DashScope API key (`ALIYUN_API_KEY`)

## Local setup

```bash
cd chat-service
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env        # set CHAT_DB_URL and ALIYUN_API_KEY
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

## Docker

```bash
docker build -t dublin-bikes-chat-service:local .
docker run --rm -p 8002:8002 --env-file .env dublin-bikes-chat-service:local
```

## CI/CD

See [Jenkinsfile](./Jenkinsfile). Jenkins credential: `dublin-bikes-chat-service.env` (not committed).
