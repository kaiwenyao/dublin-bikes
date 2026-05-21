# Dublin Bikes

前后端单仓（monorepo）：根目录下分两个独立工程。

```
dublin-bikes/
├── backend/      # Spring Boot (Java 17, Maven)
│   ├── src/
│   └── pom.xml
├── frontend/     # 前端工程 (待初始化)
│   └── ...
├── devplan/      # 开发文档
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

详见 [devplan/](./devplan/)。

## Frontend

待初始化。建议在 `frontend/` 下 `npm create vite@latest .`（或 `pnpm create next-app` 等），选定框架后补充本节。

## 开发文档

- [00-overview](./devplan/00-overview.md)
- [01-architecture](./devplan/01-architecture.md)
- [02-data-model](./devplan/02-data-model.md)
- [03-configuration-and-dependencies](./devplan/03-configuration-and-dependencies.md)
- [04-modules](./devplan/04-modules.md)
- [05-testing-and-roadmap](./devplan/05-testing-and-roadmap.md)
