# 🚲 Dublin Bikes

A full-stack Dublin Bikes (公共自行车) platform: live station availability, weather, journey planning, ML-based bike-availability prediction, user accounts, and an LLM chat assistant. Monorepo with a Spring Boot backend, a React frontend, and two Python microservices.

## 📋 Table of Contents

- [✨ Features](#-features)
- [🧱 Architecture](#-architecture)
- [🚀 Getting Started](#-getting-started)
  - [🔧 Installation](#-installation)
  - [⚙️ Configuration](#-configuration)
- [💻 Usage](#-usage)
- [🧬 Testing](#-testing)
- [🤝 Contributing](#-contributing)
- [📝 License](#-license)
- [📧 Contact](#-contact)

## ✨ Features

- 🚲 **Station data** — metadata, real-time availability, and historical snapshots via `/api/stations`.
- 🌦️ **Weather** — cached Dublin forecast served from the database via `/api/weather`.
- 🗺️ **Journey planning** — walk → cycle → walk minimum-total-time search over the nearest stations (Google Maps Distance Matrix + Geocoding).
- 🤖 **Bike-availability prediction** — a scikit-learn model served as a FastAPI microservice, exposed through `/api/stations/{n}/prediction`.
- 🔐 **User accounts** — registration, email verification (code + link), JWT access/refresh, and instant logout via `token_version`.
- 💬 **LLM chat assistant** — DeepSeek + LangChain in a standalone Python service; Spring proxies SSE streaming and enforces session ACLs.

## 🧱 Architecture

```
dublin-bikes/
├── backend/             # Spring Boot 3.5 (Java 21, Maven) — REST API, auth, proxies   :8080
├── frontend/            # React + Vite + TypeScript SPA                                 :5173
├── chat-service/        # FastAPI + LangChain + DeepSeek (LLM, message_store)           :8002
├── prediction-service/  # FastAPI + scikit-learn (bike-availability model)              :8001
├── scraper/             # External data ingestion (JCDecaux / weather)
└── docs/                # Supplementary API & security notes
```

The backend is the single entry point for the frontend; it handles persistence (PostgreSQL via Flyway + JPA), JWT auth, and journey logic, and proxies chat/prediction requests to the two Python services.

## 🚀 Getting Started

### 🔧 Installation

1. Clone the repository:
   ```bash
   git clone git@github.com:kaiwenyao/dublin-bikes.git
   cd dublin-bikes
   ```

2. Backend (Spring Boot, Java 21):
   ```bash
   cd backend
   ./mvnw clean package
   cd ..
   ```

3. Frontend (React + Vite):
   ```bash
   cd frontend
   npm install
   cd ..
   ```

4. Python microservices (chat & prediction):
   ```bash
   for svc in chat-service prediction-service; do
     cd "$svc"
     python -m venv .venv && source .venv/bin/activate
     pip install -r requirements.txt
     deactivate
     cd ..
   done
   ```

### ⚙️ Configuration

Each service reads configuration from environment variables. Copy the provided example files and fill in real values:

```bash
cp backend/src/main/resources/application-dev.yaml.example backend/src/main/resources/application-dev.yaml
cp chat-service/.env.example       chat-service/.env
cp prediction-service/.env.example prediction-service/.env
cp frontend/.env.example           frontend/.env
```

**Backend** (`application-dev.yaml`):

| Variable | Purpose |
|---|---|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL connection |
| `JWT_SECRET_KEY` / `JWT_REFRESH_SECRET_KEY` | JWT signing secrets (≥32 chars) |
| `MAIL_SERVER` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | SMTP for verification emails |
| `GOOGLE_MAPS_API_KEY` | Journey planning (Distance Matrix + Geocoding) |
| `CHAT_SERVICE_BASE_URL` | Defaults to `http://localhost:8002` |
| `PREDICTION_SERVICE_BASE_URL` | Defaults to `http://localhost:8001` |

**chat-service** (`.env`): `CHAT_DB_URL`, `DEEPSEEK_API_KEY` (optional: `DEEPSEEK_BASE_URL`, `DEEPSEEK_MODEL`).

**prediction-service** (`.env`): `HF_TOKEN` — downloads `bike_availability_model.pkl` + `model_features.pkl` from Hugging Face Hub at startup. Or drop the `.pkl` files into `prediction-service/machine_learning/` yourself.

**frontend** (`.env`): `VITE_GOOGLE_MAPS_API_KEY` (browser-exposed; not a secret).

## 💻 Usage

Start each service in its own terminal:

```bash
# 1. prediction-service  → http://localhost:8001
cd prediction-service && source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8001

# 2. chat-service        → http://localhost:8002
cd chat-service && source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8002

# 3. backend             → http://localhost:8080
cd backend && ./mvnw spring-boot:run

# 4. frontend            → http://localhost:5173
cd frontend && npm run dev
```

Open <http://localhost:5173> in your browser. Verify the backend is healthy:

```bash
curl http://localhost:8080/actuator/health     # {"status":"UP"}
```

## 🧬 Testing

```bash
cd backend             && ./mvnw test                    # Spring Boot (JUnit 5)
cd chat-service        && pytest                          # FastAPI chat service
cd prediction-service  && pytest                          # FastAPI prediction service
cd frontend            && npm test                        # frontend unit tests
```

## 🤝 Contributing

1. Fork the repository.
2. Create a feature branch: `git checkout -b feat/your-feature`.
3. Commit your changes using Conventional Commits: `git commit -m "feat: add your feature"`.
4. Push to your fork: `git push origin feat/your-feature`.
5. Open a Pull Request against `main`.

## 📝 License

No license file is currently included. Add a `LICENSE` file to define usage terms.

## 📧 Contact

- 👤 **Maintainer:** [@kaiwenyao](https://github.com/kaiwenyao)
- 🐛 **Issues:** <https://github.com/kaiwenyao/dublin-bikes/issues>
