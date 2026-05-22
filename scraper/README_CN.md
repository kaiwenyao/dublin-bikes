# 🚀 Dublin Bikes 数据采集器

> [🇬🇧 English](README.md)

**Dublin Bikes Scraper** 是一个 ✨ 独立的数据采集服务 ✨，用于持续抓取都柏林共享单车站点的实时可用性数据以及本地天气预报，并将其持久化到 MySQL 数据库中。无论你是在构建实时仪表盘、训练需求预测模型，还是单纯热爱开放数据 —— 这个采集器都能满足你的需求！🎉

> **共享数据库**：表结构（`station`、`availability`、`weather_forecast`）由配套的 [flask-app](https://github.com/ucdse/flask-app) 维护。本采集器仅负责写入数据 —— **不拥有或运行迁移脚本**。

---

## 📋 目录
- [✨ 功能特性](#-功能特性)
- [🏗️ 架构设计](#-架构设计)
- [🚀 快速开始](#-快速开始)
  - [🔧 前置条件](#-前置条件)
  - [🗄️ 数据库与迁移](#-数据库与迁移)
  - [⚙️ 本地安装](#-本地安装)
  - [🐳 Docker 安装](#-docker-安装)
  - [🔧 配置说明](#-配置说明)
- [🧬 测试](#-测试)
- [💻 使用方式](#-使用方式)
- [📁 项目结构](#-项目结构)
- [🔄 CI/CD](#-cicd)
- [🤝 贡献指南](#-贡献指南)
- [📝 许可证](#-许可证)
- [📧 联系方式](#-联系方式)

---

## ✨ 功能特性
- **🚲 单车站点采集**：每 5 分钟从 JCDecaux API 获取实时站点数据（可用性、状态、位置）。🌟
- **🌤️ 天气预报采集**：每小时从 OpenWeatherMap 收集天气预报（温度、湿度、风速、紫外线指数等）。
- **🔄 双线程架构**：站点采集和天气采集在两个独立的线程中并发运行，各自拥有独立的采集间隔和重试机制。🔥
- **🐳 Docker 优先部署**：内置生产级 `Dockerfile`（基于 Python 3.12-slim，非 root 用户运行）。🐳
- **⚡ 故障自动重试**：站点采集器在出错后会在 `RETRY_INTERVAL_SECONDS`（默认 60 秒）后自动重试。天气采集器内部处理瞬时 API 错误，并在下一个定时周期自动重试。⚡
- **🧹 智能数据清理**：天气采集器自动清理过期的预报数据，仅保留未来 48 小时的数据。🧹
- **🗄️ 智能插入策略**：站点记录在首次 encounter 时插入；可用性数据每轮更新。天气预报采用 upsert 策略避免重复。🗄️

---

## 🏗️ 架构设计

```
┌──────────────────────────────────────────────────┐
│                 main_scraper.py                   │
│  ┌─────────────────────┐ ┌─────────────────────┐ │
│  │  站点采集线程        │ │  天气采集线程        │ │
│  │  (每 5 分钟)         │ │  (每 1 小时)         │ │
│  │  ┌───────────────┐  │ │  ┌───────────────┐  │ │
│  │  │ JCDecaux API  │  │ │  │ OpenWeatherMap │  │ │
│  │  └───────┬───────┘  │ │  └───────┬───────┘  │ │
│  │          │           │ │          │           │ │
│  │          ▼           │ │          ▼           │ │
│  │  ┌───────────────┐  │ │  ┌───────────────┐  │ │
│  │  │  MySQL 数据库  │  │ │  │  MySQL 数据库  │  │ │
│  │  │ (station,     │  │ │  │ (weather_     │  │ │
│  │  │  availability)│  │ │  │  forecast)    │  │ │
│  │  └───────────────┘  │ │  └───────────────┘  │ │
│  └─────────────────────┘ └─────────────────────┘ │
└──────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 🔧 前置条件
- **Python 3.11+**（或 Docker）
- 已迁移的 **MySQL** 数据库（详见 [数据库与迁移](#-数据库与迁移)）
- **JCDecaux API 密钥** —— [在此申请](https://developer.jcdecaux.com/)
- **OpenWeatherMap API 密钥** —— [在此注册](https://openweathermap.org/api)（可选，用于天气采集）

### 🗄️ 数据库与迁移

表结构由 **flask-app** 维护。首次运行采集器之前（或任何迁移变更后），请在 flask-app 目录下执行迁移：

```bash
cd ../flask-app && flask --app app:create_app db upgrade
```

数据库就绪后，选择以下两种安装方式之一。

### ⚙️ 本地安装

1. **克隆仓库：**
   ```bash
   git clone https://github.com/ucdse/scraper.git
   cd scraper
   ```

2. **创建并激活虚拟环境（推荐）：**
   ```bash
   # 创建 Conda 环境
   conda create -n scraper python=3.11 -y

   # 激活环境
   conda activate scraper
   ```

3. **安装依赖：**
   ```bash
   pip install -r requirements.txt
   ```

4. **配置环境变量：**
   ```bash
   cp .env.example .env
   ```
   编辑 `.env` —— 详见下方的 [配置说明](#-配置说明)。

5. **确保数据库已就绪：**
   - MySQL 服务已运行且可访问
   - flask-app 的迁移已应用（详见 [数据库与迁移](#-数据库与迁移)）

6. **运行采集器：**

   | 命令 | 说明 |
   |---------|-------------|
   | `python fetch_stations.py` | 一次性抓取 —— 将站点数据保存为 JSON 文件 |
   | `python main_scraper.py` | 持续采集 —— 轮询 API 并写入数据库 |

### 🐳 Docker 安装

1. **构建镜像：**
   ```bash
   docker build -t kaiwenyao/scraper:latest .
   ```

2. **创建 Docker 网络**（如与 flask-app 共享数据库）：
   ```bash
   docker network create flask-app
   ```

3. **运行容器：**
   ```bash
   docker run -d \
     --name scraper \
     --restart unless-stopped \
     --network flask-app \
     --env-file /path/to/.env \
     kaiwenyao/scraper:latest
   ```

   | 参数 | 用途 |
   |------|---------|
   | `--restart unless-stopped` | 主机重启后自动重启 |
   | `--network flask-app` | 与 flask-app 共享网络以访问数据库 |
   | `--env-file` | 从 `.env` 文件传入环境变量 |

   容器默认执行 `python main_scraper.py`（持续采集 + 数据库写入）。

---

### 🔧 配置说明

所有设置均通过环境变量管理（由 `python-dotenv` 从 `.env` 加载）。将 `.env.example` 复制为起点：

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

| 变量 | 必填 | 默认值 | 说明 |
|----------|----------|---------|-------------|
| `DATABASE_URL` | ✅ | — | MySQL 连接字符串，例如 `mysql+pymysql://user:password@host:3306/dublinbikes` |
| `JCDECAUX_API_KEY` | ✅ | — | JCDecaux API 密钥，用于获取单车站点数据 |
| `JCDECAUX_CONTRACT` | ✅ | `dublin` | JCDecaux 合同名称 |
| `OPENWEATHER_API_KEY` | | — | OpenWeatherMap API 密钥（可选 —— 如未设置则跳过天气采集） |
| `SCRAPE_INTERVAL_SECONDS` | | `300` | 单车站点采集间隔（秒） |
| `RETRY_INTERVAL_SECONDS` | | `60` | 失败后重试等待时间（秒） |
| `WEATHER_SCRAPE_INTERVAL_SECONDS` | | `3600` | 天气采集间隔（秒） |
| `WEATHER_CITY` | | `Dublin,IE` | 天气预报的目标城市 |
| `OUTPUT_JSON` | | `stations.json` | `fetch_stations.py` 的输出文件 |

---

## 🧬 测试

本项目目前不包含自动化测试套件。该采集器设计为长期运行的数据采集服务，主要通过针对实时 API 和 MySQL 数据库的手动集成测试进行验证。

如果你想贡献测试，请查看下方的 [贡献指南](#-贡献指南) —— 我们非常欢迎！🙌

---

## 💻 使用方式

### 一次性站点抓取（JSON 输出）
```bash
python fetch_stations.py
# 输出：stations.json，包含所有站点数据
```

### 持续采集（数据库写入）
```bash
python main_scraper.py
# 日志输出示例：
# [2026-04-19 10:00:00] 正在采集站点数据...
# [2026-04-19 10:00:02] 完成 | 获取 110 条站点记录，0 个新站点，写入 110 条可用性记录 | 耗时：1.85s
# [2026-04-19 10:00:00] 正在采集天气 (Dublin,IE)...
# [2026-04-19 10:00:03] 天气采集完成 | 插入：48，更新：0 | 耗时：2.30s
```

`main_scraper.py` 内部运行两个并发线程：
- **站点采集线程**：每 `SCRAPE_INTERVAL_SECONDS`（默认 5 分钟）轮询 JCDecaux API
- **天气采集线程**：每 `WEATHER_SCRAPE_INTERVAL_SECONDS`（默认 1 小时）轮询 OpenWeatherMap

两个线程都能自动从错误中恢复。站点线程在 `RETRY_INTERVAL_SECONDS`（默认 60 秒）后重试。天气线程内部处理 API 错误，并在下一个整点周期重试。

---

## 📁 项目结构
```
scraper/
├── main_scraper.py       # 入口 —— 在两个并发线程中运行采集器
├── fetch_stations.py     # JCDecaux API 客户端 —— 抓取并保存站点 JSON
├── fetch_weather.py      # OpenWeatherMap 客户端 —— 抓取并 upsert 天气预报
├── config.py             # 基于环境的配置（无 Flask 依赖）
├── database.py           # SQLAlchemy 引擎与会话工厂
├── models.py             # ORM 模型：Station、Availability
├── models_weather.py     # ORM 模型：WeatherForecast
├── requirements.txt     # Python 依赖
├── Dockerfile            # 生产级容器（Python 3.12-slim，非 root 用户）
├── Jenkinsfile           # CI/CD 流水线 —— 构建、推送、部署至 EC2
├── .env.example          # 环境变量模板
├── .gitignore
└── .dockerignore
```

### 核心依赖

| 包 | 用途 |
|---------|---------|
| [SQLAlchemy](https://www.sqlalchemy.org/) 2.0 | ORM 与数据库会话管理 |
| [PyMySQL](https://github.com/PyMySQL/PyMySQL) | SQLAlchemy 的 MySQL 驱动 |
| [requests](https://docs.python-requests.org/) | OpenWeatherMap API 的 HTTP 客户端 |
| [python-dotenv](https://github.com/theskumar/python-dotenv) | 将 `.env` 变量加载到 `os.environ` |
| [cryptography](https://cryptography.io/) | PyMySQL 的安全连接支持 |

---

## 🔄 CI/CD

`Jenkinsfile` 定义了一个 4 阶段流水线：

| 阶段 | 说明 |
|-------|-------------|
| **1. 拉取代码** | 从 SCM 检出 |
| **2. Python 语法检查** | 编译所有 `.py` 文件以验证语法 |
| **3. 构建并推送 Docker 镜像** | 构建镜像 → 推送到 Docker Hub |
| **4. 部署至 EC2** | 仅在 `main` 分支 —— 拉取镜像，在 EC2 上重启容器 |

部署使用 Jenkins 参数中定义的相同镜像名、容器名和 `.env` 路径。

---

## 🤝 贡献指南

我们欢迎贡献！🎉 如果你想参与贡献，请按以下步骤操作：

1. **Fork** 本仓库。

2. **创建新分支：**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **提交你的更改：**
   ```bash
   git commit -m "Add your awesome feature"
   ```

4. **推送到分支：**
   ```bash
   git push origin feature/your-feature-name
   ```

5. **发起 Pull Request。** 🚀

---

## 📝 许可证

本项目目前未指定开源许可证。所有权利由仓库所有者保留。

---

## 📧 联系方式

如有任何问题或反馈，欢迎联系我们：

- **电子邮件**：目前请通过 [GitHub issue](https://github.com/ucdse/scraper/issues/new) 联系 —— 项目专用邮箱即将推出
- **GitHub Issues**: [提交 Issue](https://github.com/ucdse/scraper/issues) 🐛
- **组织**：[UCD Software Engineering](https://github.com/ucdse)

---

由 [UCD Software Engineering](https://github.com/ucdse) 团队用 ❤️ 打造。祝你采集愉快！🎉
