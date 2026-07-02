# 🚲 Dublin Bikes

[English](./README.md) · **简体中文**

一个全栈的 Dublin Bikes（都柏林公共自行车）平台：实时站点可用性、天气、行程规划、基于机器学习的可借车辆预测、用户账户，以及 LLM 对话助手。采用单仓（monorepo）结构，包含 Spring Boot 后端、React 前端和两个 Python 微服务。

## 📋 目录

- [✨ 功能特性](#-功能特性)
- [🧱 架构](#-架构)
- [🚀 快速开始](#-快速开始)
  - [🔧 安装](#-安装)
  - [⚙️ 配置](#️-配置)
- [💻 运行](#-运行)
- [🧬 测试](#-测试)
- [🤝 贡献](#-贡献)
- [📝 许可证](#-许可证)
- [📧 联系方式](#-联系方式)

## ✨ 功能特性

- 🚲 **站点数据** — 通过 `/api/stations` 提供站点元数据、实时可用性和历史快照。
- 🌦️ **天气** — 通过 `/api/weather` 提供从数据库读取的都柏林天气缓存。
- 🗺️ **行程规划** — 在最近的若干站点之间进行「步行 → 骑行 → 步行」全局最小总时长搜索（Google Maps Distance Matrix + Geocoding）。
- 🤖 **可借车辆预测** — 以 FastAPI 微服务承载 scikit-learn 模型，通过 `/api/stations/{n}/prediction` 对外暴露。
- 🔐 **用户账户** — 注册、邮件验证（验证码 + 链接）、JWT access/refresh，以及通过 `token_version` 实现的即时登出。
- 💬 **LLM 对话助手** — DeepSeek + LangChain 独立 Python 服务；Spring 代理 SSE 流式输出并执行会话 ACL。

## 🧱 架构

```
dublin-bikes/
├── backend/             # Spring Boot 3.5（Java 21，Maven）—— REST API、鉴权、代理   :8080
├── frontend/            # React + Vite + TypeScript 单页应用                          :5173
├── chat-service/        # FastAPI + LangChain + DeepSeek（LLM、message_store）        :8002
├── prediction-service/  # FastAPI + scikit-learn（可借车辆预测模型）                  :8001
├── scraper/             # 外部数据抓取（JCDecaux / 天气）
└── docs/                # 补充的 API 与安全说明
```

后端是前端的唯一入口：负责持久化（PostgreSQL + Flyway + JPA）、JWT 鉴权和行程逻辑，并将对话/预测请求代理到两个 Python 服务。

## 🚀 快速开始

### 🔧 安装

1. 克隆仓库：
   ```bash
   git clone git@github.com:kaiwenyao/dublin-bikes.git
   cd dublin-bikes
   ```

2. 后端（Spring Boot，Java 21）：
   ```bash
   cd backend
   ./mvnw clean package
   cd ..
   ```

3. 前端（React + Vite）：
   ```bash
   cd frontend
   npm install
   cd ..
   ```

4. Python 微服务（chat & prediction）：
   ```bash
   for svc in chat-service prediction-service; do
     cd "$svc"
     python -m venv .venv && source .venv/bin/activate
     pip install -r requirements.txt
     deactivate
     cd ..
   done
   ```

### ⚙️ 配置

每个服务都从环境变量读取配置。复制提供的示例文件并填入真实值：

```bash
cp backend/src/main/resources/application-dev.yaml.example backend/src/main/resources/application-dev.yaml
cp chat-service/.env.example       chat-service/.env
cp prediction-service/.env.example prediction-service/.env
cp frontend/.env.example           frontend/.env
```

**后端**（`application-dev.yaml`）：

| 变量 | 用途 |
|---|---|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL 连接 |
| `JWT_SECRET_KEY` / `JWT_REFRESH_SECRET_KEY` | JWT 签名密钥（≥32 字符） |
| `MAIL_SERVER` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | 验证邮件的 SMTP |
| `GOOGLE_MAPS_API_KEY` | 行程规划（Distance Matrix + Geocoding） |
| `CHAT_SERVICE_BASE_URL` | 默认 `http://localhost:8002` |
| `PREDICTION_SERVICE_BASE_URL` | 默认 `http://localhost:8001` |

**chat-service**（`.env`）：`CHAT_DB_URL`、`DEEPSEEK_API_KEY`（可选：`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`）。

**prediction-service**（`.env`）：`HF_TOKEN` —— 容器启动时从 Hugging Face Hub 下载 `bike_availability_model.pkl` + `model_features.pkl`。也可自行将 `.pkl` 文件放入 `prediction-service/machine_learning/`。

**frontend**（`.env`）：`VITE_GOOGLE_MAPS_API_KEY`（会暴露给浏览器，非机密）。

## 💻 运行

在各自的终端中分别启动每个服务：

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

在浏览器中打开 <http://localhost:5173>。验证后端健康状态：

```bash
curl http://localhost:8080/actuator/health     # {"status":"UP"}
```

## 🧬 测试

```bash
cd backend             && ./mvnw test                    # Spring Boot（JUnit 5）
cd chat-service        && pytest                          # FastAPI chat 服务
cd prediction-service  && pytest                          # FastAPI prediction 服务
cd frontend            && npm test                        # 前端单元测试
```

## 🤝 贡献

1. Fork 本仓库。
2. 创建特性分支：`git checkout -b feat/your-feature`。
3. 使用 Conventional Commits 提交：`git commit -m "feat: add your feature"`。
4. 推送到你的 fork：`git push origin feat/your-feature`。
5. 向 `main` 发起 Pull Request。

## 📝 许可证

当前仓库未包含许可证文件。如需明确使用条款，请添加 `LICENSE` 文件。

## 📧 联系方式

- 👤 **维护者：** [@kaiwenyao](https://github.com/kaiwenyao)
- 🐛 **问题反馈：** <https://github.com/kaiwenyao/dublin-bikes/issues>
