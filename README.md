# Dublin Bikes

前后端单仓（monorepo）：根目录下分两个独立工程。

```
dublin-bikes/
├── backend/         # Spring Boot (Java 21, Maven)
│   ├── src/
│   ├── devplan/     # 开发文档（后端重构计划）
│   └── pom.xml
├── frontend/        # 前端工程 (待初始化)
│   └── ...
├── chat-service/    # 独立 Python LLM 微服务 (FastAPI + LangChain + Qwen，S5 落地)
│   └── ...
├── .gitignore
└── README.md
```

## Backend

Spring Boot 工程，Maven 构建。

```bash
cd backend
./mvnw spring-boot:run    # 启动
./mvnw test               # 测试
./mvnw clean package      # 打包
```

详见 [backend/devplan/](./backend/devplan/)。

## Frontend

待初始化。建议在 `frontend/` 下 `npm create vite@latest .`（或 `pnpm create next-app` 等），选定框架后补充本节。

## Chat Service (Python)

独立 Python 微服务，承担所有 LLM 逻辑（LangChain + Qwen + `message_store` 读写）。默认端口 **8002**；Spring 通过 `app.chat-service.base-url` 代理（见 [backend/devplan/04-modules.md §5.7](./backend/devplan/04-modules.md)）。

```bash
cd chat-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 设置 CHAT_DB_URL、ALIYUN_API_KEY
uvicorn main:app --host 0.0.0.0 --port 8002
curl http://localhost:8002/health
```

CI/CD 见 [chat-service/Jenkinsfile](./chat-service/Jenkinsfile)。更多说明见 [chat-service/README.md](./chat-service/README.md)。

## 开发文档

- [00-overview](./backend/devplan/00-overview.md)
- [01-architecture](./backend/devplan/01-architecture.md)
- [02-data-model](./backend/devplan/02-data-model.md)
- [03-configuration-and-dependencies](./backend/devplan/03-configuration-and-dependencies.md)
- [04-modules](./backend/devplan/04-modules.md)
- [05-testing-and-roadmap](./backend/devplan/05-testing-and-roadmap.md)
