# Dublin Bikes API — Postman

Postman workspace **Dublin Bikes** contains:

- **Collection:** `Dublin Bikes API` — 12 requests with automated tests
- **Environment:** `Dublin Bikes - Local` — `baseUrl`, auth tokens, test variables
- **Spec:** `Dublin Bikes API` (OpenAPI 3.0 in Spec Hub)

## Quick start

1. Start backend: `cd backend && mvn spring-boot:run` (port **5000**)
2. In Postman, select workspace **Dublin Bikes** and environment **Dublin Bikes - Local**
3. Run public endpoints (Stations, Weather) or auth flow:
   - `Users → Session → Login` (saves tokens)
   - `Users → Profile → Get Current User`
4. **Collection Runner:** run entire collection; Login should run before protected routes

## Auth

| Route | Auth |
|-------|------|
| `GET /api/stations/**`, `GET /api/weather`, `POST /api/journey/plan` | Public |
| `POST /api/users/register`, login, refresh, activate* | Public |
| `GET /api/users/me`, `POST /api/users/logout` | Bearer `{{access_token}}` |

Response envelope: `{ "code": 0, "msg": "ok", "data": ... }`
