# 🚀 Dublin Bikes Scraper

> [🇨🇳 中文版](README_CN.md)

**Dublin Bikes Scraper** is a ✨ standalone data collection service ✨ designed to continuously scrape Dublin's shared bike station availability and local weather forecasts, then persist them to a MySQL database. Whether you're building a real-time dashboard, training a demand-forecasting model, or just love open data — this scraper has you covered! 🎉

> **Shared Database**: Table schemas (`station`, `availability`, `weather_forecast`) are maintained by the companion [flask-app](https://github.com/ucdse/flask-app). This scraper only writes data — it does **not** own or run migrations.

---

## 📋 Table of Contents
- [✨ Features](#-features)
- [🏗️ Architecture](#-architecture)
- [🚀 Getting Started](#-getting-started)
  - [🔧 Prerequisites](#-prerequisites)
  - [🗄️ Database and Migrations](#-database-and-migrations)
  - [⚙️ Installation (Local)](#-installation-local)
  - [🐳 Installation (Docker)](#-installation-docker)
  - [🔧 Configuration](#-configuration)
- [🧬 Testing](#-testing)
- [💻 Usage](#-usage)
- [📁 Project Structure](#-project-structure)
- [🔄 CI/CD](#-cicd)
- [🤝 Contributing](#-contributing)
- [📝 License](#-license)
- [📧 Contact](#-contact)

---

## ✨ Features
- **🚲 Bike Station Scraping**: Fetches real-time station data (availability, status, location) from the JCDecaux API every 5 minutes. 🌟
- **🌤️ Weather Forecast Scraping**: Collects hourly weather forecasts (temperature, humidity, wind, UV index, etc.) from OpenWeatherMap every hour.
- **🔄 Dual-Thread Architecture**: Station and weather scraping run concurrently in separate threads with independent intervals and retry logic. 🔥
- **🐳 Docker-First Deployment**: Ships with a production-ready `Dockerfile` (Python 3.12-slim, non-root user). 🐳
- **⚡ Auto-Retry on Failure**: The station scraper catches errors and retries after `RETRY_INTERVAL_SECONDS` (default 60s). The weather scraper handles transient API errors internally and retries on the next scheduled cycle. ⚡
- **🧹 Smart Data Cleanup**: Weather scraper automatically purges expired forecasts, keeping only the next 48 hours. 🧹
- **🗄️ Smart Inserts**: Station records are inserted on first encounter; availability data is updated each cycle. Weather forecasts are upserted to avoid duplicates. 🗄️

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────┐
│                 main_scraper.py                   │
│  ┌─────────────────────┐ ┌─────────────────────┐ │
│  │  Station Thread     │ │  Weather Thread      │ │
│  │  (every 5 min)      │ │  (every 1 hour)      │ │
│  │  ┌───────────────┐  │ │  ┌───────────────┐  │ │
│  │  │ JCDecaux API  │  │ │  │ OpenWeatherMap │  │ │
│  │  └───────┬───────┘  │ │  └───────┬───────┘  │ │
│  │          │           │ │          │           │ │
│  │          ▼           │ │          ▼           │ │
│  │  ┌───────────────┐  │ │  ┌───────────────┐  │ │
│  │  │  MySQL DB     │  │ │  │  MySQL DB      │  │ │
│  │  │ (station,     │  │ │  │ (weather_     │  │ │
│  │  │  availability)│  │ │  │  forecast)    │  │ │
│  │  └───────────────┘  │ │  └───────────────┘  │ │
│  └─────────────────────┘ └─────────────────────┘ │
└──────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### 🔧 Prerequisites
- **Python 3.11+** (or Docker)
- **MySQL** database accessible and migrated (see [Database and Migrations](#-database-and-migrations))
- **JCDecaux API Key** — [Request here](https://developer.jcdecaux.com/)
- **OpenWeatherMap API Key** — [Sign up here](https://openweathermap.org/api) (optional, for weather scraping)

### 🗄️ Database and Migrations

Table schemas are maintained by the **flask-app**. Before running the scraper for the first time (or after any migration change), run migrations in the flask-app directory:

```bash
cd ../flask-app && flask --app app:create_app db upgrade
```

Once the database is ready, choose one of the two installation methods below.

### ⚙️ Installation (Local)

1. **Clone the repository:**
   ```bash
   git clone https://github.com/ucdse/scraper.git
   cd scraper
   ```

2. **Create and activate a virtual environment (recommended):**
   ```bash
   # Create Conda environment
   conda create -n scraper python=3.11 -y

   # Activate environment
   conda activate scraper
   ```

3. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

4. **Configure environment variables:**
   ```bash
   cp .env.example .env
   ```
   Edit `.env` — see [Configuration](#-configuration) below for details.

5. **Ensure the database is ready:**
   - MySQL service is running and accessible
   - flask-app migrations have been applied (see [Database and Migrations](#-database-and-migrations))

6. **Run the scraper:**

   | Command | Description |
   |---------|-------------|
   | `python fetch_stations.py` | One-off fetch — saves station data to a JSON file |
   | `python main_scraper.py` | Continuous scraping — polls APIs and writes to database |

### 🐳 Installation (Docker)

1. **Build the image:**
   ```bash
   docker build -t kaiwenyao/scraper:latest .
   ```

2. **Create a Docker network** (if sharing a database with flask-app):
   ```bash
   docker network create flask-app
   ```

3. **Run the container:**
   ```bash
   docker run -d \
     --name scraper \
     --restart unless-stopped \
     --network flask-app \
     --env-file /path/to/.env \
     kaiwenyao/scraper:latest
   ```

   | Flag | Purpose |
   |------|---------|
   | `--restart unless-stopped` | Auto-restart on host reboot |
   | `--network flask-app` | Share network with flask-app for database access |
   | `--env-file` | Pass environment variables from a `.env` file |

   The container defaults to `python main_scraper.py` (continuous scraping + database writes).

---

### 🔧 Configuration

All settings are managed via environment variables (loaded from `.env` via `python-dotenv`). Copy `.env.example` as a starting point:

```env
DATABASE_URL=mysql+pymysql://user:password@127.0.0.1:3306/dublinbikes
JCDECAUX_API_KEY=your_jcdecaux_api_key
JCDECAUX_CONTRACT=dublin
SCRAPE_INTERVAL_SECONDS=300
RETRY_INTERVAL_SECONDS=60
WEATHER_SCRAPE_INTERVAL_SECONDS=3600
OUTPUT_JSON=stations.json
JCDECAUX_BASE_URL=https://api.jcdecaux.com/vls/v1/stations
OPENWEATHER_API_KEY=your_openweather_api_key
OPENWEATHER_GEOCODING_URL=http://api.openweathermap.org/geo/1.0/direct
OPENWEATHER_ONECALL_URL=https://api.openweathermap.org/data/3.0/onecall
OPENWEATHER_FORECAST_URL=https://api.openweathermap.org/data/2.5/forecast
WEATHER_CITY=Dublin,IE
```

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | ✅ | — | MySQL connection string, e.g. `mysql+pymysql://user:password@host:3306/dublinbikes` |
| `JCDECAUX_API_KEY` | ✅ | — | JCDecaux API key for bike station data |
| `JCDECAUX_CONTRACT` | ✅ | `dublin` | JCDecaux contract name |
| `OPENWEATHER_API_KEY` | | — | OpenWeatherMap API key (optional — weather scraping is skipped if unset) |
| `SCRAPE_INTERVAL_SECONDS` | | `300` | Interval between bike station scrapes (seconds) |
| `RETRY_INTERVAL_SECONDS` | | `60` | Wait time before retrying after a failure (seconds) |
| `WEATHER_SCRAPE_INTERVAL_SECONDS` | | `3600` | Interval between weather scrapes (seconds) |
| `WEATHER_CITY` | | `Dublin,IE` | Target city for weather forecasts |
| `OUTPUT_JSON` | | `stations.json` | Output file for `fetch_stations.py` |

---

## 🧬 Testing

This project does not currently include an automated test suite. The scraper is designed as a long-running data collection service and is primarily validated through manual integration testing against live APIs and the MySQL database.

If you'd like to contribute tests, check out [Contributing](#-contributing) below — we'd love to have them! 🙌

---

## 💻 Usage

### One-off Station Fetch (JSON output)
```bash
python fetch_stations.py
# Output: stations.json with all station data
```

### Continuous Scraping (Database writes)
```bash
python main_scraper.py
# Log output example:
# [2026-04-19 10:00:00] Scraping stations...
# [2026-04-19 10:00:02] Done | Fetched 110 station records, 0 new stations, 110 availability records written | Elapsed: 1.85s
# [2026-04-19 10:00:00] Scraping weather (Dublin,IE)...
# [2026-04-19 10:00:03] Weather done | Insert: 48, Update: 0 | Elapsed: 2.30s
```

Two concurrent threads run inside `main_scraper.py`:
- **Station thread**: Polls JCDecaux API every `SCRAPE_INTERVAL_SECONDS` (default 5 min)
- **Weather thread**: Polls OpenWeatherMap every `WEATHER_SCRAPE_INTERVAL_SECONDS` (default 1 hour)

Both threads recover automatically from errors. The station thread retries after `RETRY_INTERVAL_SECONDS` (default 60s). The weather thread handles API errors internally and retries on the next hourly cycle.

---

## 📁 Project Structure
```
scraper/
├── main_scraper.py       # Entry point — runs both scrapers in concurrent threads
├── fetch_stations.py     # JCDecaux API client — fetches & saves station JSON
├── fetch_weather.py      # OpenWeatherMap client — fetches & upserts weather forecasts
├── config.py             # Environment-based configuration (no Flask dependency)
├── database.py           # SQLAlchemy engine & session factory
├── models.py             # ORM models: Station, Availability
├── models_weather.py     # ORM model: WeatherForecast
├── requirements.txt     # Python dependencies
├── Dockerfile            # Production-ready container (Python 3.12-slim, non-root)
├── Jenkinsfile           # CI/CD pipeline — build, push, deploy to EC2
├── .env.example          # Template for environment variables
├── .gitignore
└── .dockerignore
```

### Key Dependencies

| Package | Purpose |
|---------|---------|
| [SQLAlchemy](https://www.sqlalchemy.org/) 2.0 | ORM & database session management |
| [PyMySQL](https://github.com/PyMySQL/PyMySQL) | MySQL driver for SQLAlchemy |
| [requests](https://docs.python-requests.org/) | HTTP client for OpenWeatherMap API |
| [python-dotenv](https://github.com/theskumar/python-dotenv) | Load `.env` variables into `os.environ` |
| [cryptography](https://cryptography.io/) | Secure connection support for PyMySQL |

---

## 🔄 CI/CD

The `Jenkinsfile` defines a 4-stage pipeline:

| Stage | Description |
|-------|-------------|
| **1. Pull Code** | Checkout from SCM |
| **2. Python Syntax Check** | Compile all `.py` files to verify syntax |
| **3. Build & Push Docker Image** | Build image → push to Docker Hub |
| **4. Deploy to EC2** | On `main` branch only — pull image, restart container on EC2 |

Deployment uses the same image name, container name, and `.env` path as defined in Jenkins parameters.

---

## 🤝 Contributing

We welcome contributions! 🎉 If you'd like to contribute, please follow these steps:

1. **Fork** the repository.

2. **Create a new branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Commit your changes:**
   ```bash
   git commit -m "Add your awesome feature"
   ```

4. **Push to the branch:**
   ```bash
   git push origin feature/your-feature-name
   ```

5. **Open a pull request.** 🚀

---

## 📝 License

This project does not currently have an open-source license. All rights are reserved by the repository owners.

---

## 📧 Contact

If you have any questions or feedback, feel free to reach out:

- **Email**: [Open a GitHub issue](https://github.com/ucdse/scraper/issues/new) for now — project email coming soon
- **GitHub Issues**: [Open an Issue](https://github.com/ucdse/scraper/issues) 🐛
- **Organization**: [UCD Software Engineering](https://github.com/ucdse)

---

Made with ❤️ by the [UCD Software Engineering](https://github.com/ucdse) team. Happy scraping! 🎉