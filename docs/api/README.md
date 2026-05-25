# Dublin Bikes API — Postman

Postman workspace **Dublin Bikes** contains:

- **Collection:** `Dublin Bikes API` — 17 requests (workspace **Dublin Bikes**)
- **Environment:** `Dublin Bikes - Local` — `baseUrl`, `chatServiceUrl`, auth tokens

## Quick start

1. Start backend: `cd backend && mvn spring-boot:run` (port **8080**)
2. (Optional) Start chat-service: `cd chat-service && uvicorn main:app --host 0.0.0.0 --port 8002` with `.env` (`CHAT_DB_URL`, `DEEPSEEK_API_KEY`)
3. In Postman, select workspace **Dublin Bikes** and environment **Dublin Bikes - Local**
4. Run public endpoints (Stations, Weather) or auth flow:
   - `Users → Session → Login` (saves tokens)
   - `Users → Profile → Get Current User`
5. **Chat Service** folder uses `{{chatServiceUrl}}` (default `http://localhost:8002`); **Health** works without keys
6. **Collection Runner:** run entire collection; Login should run before protected routes

## Auth

| Route | Auth |
|-------|------|
| `GET /api/stations/**`, `GET /api/weather`, `POST /api/journey/plan` | Public |
| `POST /api/users/register`, login, refresh, activate* | Public |
| `GET /api/users/me`, `POST /api/users/logout` | Bearer `{{access_token}}` |
| Chat Service on port 8002 | None (internal); Spring `/api/chat/**` will use JWT when shipped |

Response envelope (Spring): `{ "code": 0, "msg": "ok", "data": ... }`

Chat-service returns flat JSON (e.g. `{ "chat_id", "reply" }`), no envelope.

Business error codes use `HTTP_status * 100 + seq` (e.g. `40001` validation, `40401` no route, `40402` address not resolved). HTTP status is carried separately on the response line.
