# Dublin Bikes — Flask → Spring Boot 重构开发文档

> **路径说明（2026-05-21 仓库重构后）**
> 仓库已改为 monorepo 结构：根目录下分 `backend/`（Spring Boot）和 `frontend/` 两个独立工程。
> 后续文档中出现的 `src/main/...`、`pom.xml`、`application.yaml` 等路径，**实际位置统一前缀 `backend/`**，例如：
>
> - `src/main/java/dev/kaiwen/bikes/` → `backend/src/main/java/dev/kaiwen/bikes/`
> - `src/main/resources/application.yaml` → `backend/src/main/resources/application.yaml`
> - `pom.xml` → `backend/pom.xml`
>
> Java 包名 `dev.kaiwen.bikes.*` 不受影响。Maven 命令需在 `backend/` 下执行。

## 0. 文档导航

| 文件 | 内容 |
|---|---|
| `00-overview.md` | 项目目的、范围、整体迁移策略、技术栈映射、阶段路线图 |
| `01-architecture.md` | 目标分层架构、包结构、命名约定、统一响应/异常体系 |
| `02-data-model.md` | JPA 实体设计与字段映射（来自 Flask SQLAlchemy 模型） |
| `03-configuration-and-dependencies.md` | `application.yaml` 设计 + 依赖清单建议（**不修改现有 pom.xml**） |
| `04-modules.md` | 6 大业务模块详解：站点 / 天气 / 行程 / 用户认证 / LLM 对话 / 预测 |
| `05-testing-and-roadmap.md` | 测试策略、覆盖率目标、Sprint 路线图、风险与决策 |

---

## 1. 项目背景与目的

一个 **Dublin Bikes（都柏林公共自行车）** 应用的后端 API，主要为前端（Vite/React 5173 端口）和移动端提供以下能力：

1. **公共自行车数据查询**
   - 站点元数据（位置、容量、是否支持刷卡、是否奖励站点）
   - 站点实时可用性（可借车辆数 / 可还车位数 / 站点状态）
   - 历史可用性查询（按时间窗口）

2. **天气预报**
   - 缓存来自 OpenWeatherMap OneCall API 的都柏林天气
   - 对外提供与 OneCall API 兼容的结构

3. **行程规划**
   - 接收起终点（地址或经纬度），通过 Google Maps Geocoding + Distance Matrix
   - 在最近的若干个起 / 终点站之间进行 "步行 → 骑行 → 步行" 全局最小总时长搜索
   - 返回最优行程的步行/骑行用时和站点

4. **基于 ML 的站点可用车辆预测**
   - 基于已训练好的 scikit-learn 模型 (`bike_availability_model.pkl`)
   - 输入未来天气预报，预测每小时可借车辆数

5. **用户系统与认证**
   - 注册 → 邮件 6 位验证码 / 链接 token 双通道激活 → 登录 → JWT (access + refresh) → 登出（递增 `token_version` 立即失效）
   - HTML 邮件 + 异步线程池发送

6. **LLM 对话助手**
   - DeepSeek（OpenAI 兼容协议）
   - LangChain 记忆 (`SQLChatMessageHistory` → PostgreSQL `message_store` 表)
   - 普通 + SSE 流式响应、会话列表、会话历史、自动生成会话标题
   - **重构后**：LangChain 逻辑保留在 **独立 Python 微服务（`chat-service`）**，Spring Boot 仅做 JWT 鉴权、`sessions` 表 ACL 维护、HTTP/SSE 代理

**重构目标**：以 **Spring Boot 3.5.14 + Java 21** 重写后端，保持 **API 路径、请求/响应体、HTTP 语义、业务行为完全兼容**，前端无需改动即可切换。

---

## 2. 当前后端实现状态

> 对照 `backend/src/main/java` 与测试目录更新（2026-05-25）。路线图阶段见 §5；细分任务见 `05-testing-and-roadmap.md` §B。

### 2.1 工程基础（S1，已基本完成）

- ✅ 主包 `dev.kaiwen.bikes`；启动类已启用 `@ConfigurationPropertiesScan`、`@EntityScan`、`@EnableJpaRepositories`（排除默认 `UserDetailsServiceAutoConfiguration`）。
- ✅ `pom.xml`：Web、JPA、Validation、Security、Mail、Thymeleaf、Actuator、JJWT、PostgreSQL、Flyway、MapStruct、Lombok；测试用 H2 + `spring-security-test`。
- ✅ `application.yaml`：Jackson `SNAKE_CASE`、`jpa.ddl-auto=validate`、Flyway、`server.port` 默认 8080；`/actuator/health` 已暴露。
- ✅ `application-dev.yaml` / `application-prod.yaml` / `application-dev.yaml.example`：PostgreSQL 数据源；`app.jwt`、`app.verification`、`app.chat-service`（示例模板中已列出 Mail SMTP）。
- ✅ 统一响应与异常：`ApiResponse`、`ApiCodes`（含 `STATION_NOT_FOUND`）、`GlobalExceptionHandler`、`BusinessException` / `AuthException`。
- ✅ Flyway `V1__baseline.sql`（PostgreSQL DDL）；实体与表由 Hibernate `validate` 校验。
- ⏳ Jacoco 覆盖率门禁、CI pipeline、全量 Newman Postman 回归尚未落地。

### 2.2 已落地业务（S2 + S3）

| 模块 | Controller / Service | 端点 | 测试 |
|---|---|---|---|
| **Station** | `StationController`、`StationService` | `GET /api/stations/`、`/{n}/availability`、`/status` | `StationControllerTest`、`StationServiceTest`、`AvailabilityRepositoryTest` |
| **Weather** | `WeatherController`、`WeatherService` | `GET /api/weather` | `WeatherControllerTest`、`WeatherServiceTest` |
| **User** | `UserController`、`UserService`、`JwtService`、`MailService` | 注册 / 验证码 / 双通道激活 / 登录 / refresh / logout / `GET /me` | `UserControllerTest`、`UserServiceTest`、`JwtServiceTest`、`MailServiceTest`、`UserAuthIntegrationTest` |
| **Security** | `SecurityConfig`、`JwtAuthenticationFilter`、`JwtAuthenticationEntryPoint` | JWT access + refresh、`token_version` 登出失效；公开站点/天气/行程与用户注册登录路径 | 覆盖于 User 相关测试 |

- ✅ MapStruct：`StationMapper`、`UserMapper`、`WeatherMapper`。
- ✅ 数据层：全部 JPA 实体与 Repository（`Station`、`Availability`、`WeatherForecast`、`User`、`ChatSession`）；`message_store` 仍由 Python `chat-service` 独占，Spring 不映射。

### 2.3 待实现（S5–S6，下一步为 S5）

| 模块 | 现状 | 说明 |
|---|---|---|
| **Chat（S5）** | ⏳ `ChatSession` 实体、`ChatSessionRepository`、`ChatServiceProperties`、`ChatServiceConfig`（`RestClient` bean） | 需独立 Python `chat-service` + Spring 鉴权 / `sessions` ACL / SSE 代理；`SecurityConfig` 当前对 `/api/chat/**` 为 `denyAll` |
| **Prediction（S6）** | ⏳ `PredictionPointVO`；站点 Controller 未暴露 `GET /{n}/prediction` | 需独立 Python 预测微服务 + `PredictionClient` / `PredictionService` |

- ✅ Resilience4j `@Retry`（`gmaps`）+ `app.google-maps` 配置已接入 Journey。
- ⏳ Prediction 的 `@ConfigurationProperties` 与运行时配置尚未落地（参考 `03-configuration-and-dependencies.md` §4）。

### 2.4 建议的下一迭代

按 `05-testing-and-roadmap.md`，**Sprint S5 — Chat** 为下一主线（独立 Python `chat-service` + Spring SSE 代理）。S6 预测服务在 S5 之后；S4 Journey 可用 Postman 对比 Flask 做最终验收（需 `GOOGLE_MAPS_API_KEY`）。

---

## 3. 现状清单（来自 Flask 项目分析）

### 3.1 业务路由（Blueprint → Spring `@RestController`）

| Blueprint | 路径前缀 | 端点数 | 对应 Spring Controller |
|---|---|---|---|
| `station_bp` | `/api/stations` | 4 | `StationController` |
| `user_bp` | `/api/users` | 8 | `UserController` |
| `weather_bp` | `/api/weather` | 1 | `WeatherController` |
| `journey_bp` | `/api/journey` | 1 | `JourneyController` |
| `chat_bp` | `/api/chat` | 4 | `ChatController` |

详细端点和请求/响应见 `04-modules.md`。

### 3.2 数据库（PostgreSQL，通过 Spring Boot Flyway 管理）

| 表 | Flask 模型 | 说明 |
|---|---|---|
| `station` | `Station` | 站点元数据；主键 `number`（来自 JCDecaux API） |
| `availability` | `Availability` | 站点可用性历史快照；`number` FK → `station.number` |
| `user` | `User` | 用户；6 位激活码 + 64 字符 token、`token_version` 实现 JWT 失效 |
| `weather_forecast` | `WeatherForecast` | 已抓取的天气预报缓存 |
| `sessions` | `Session` | LLM 会话元数据（id, user_id, title, 时间戳） |
| `message_store` | Python chat-service 独占 | LangChain 默认会话消息表（`session_id`, `message` JSONB），Spring 不映射 JPA 实体 |

字段映射详见 `02-data-model.md`。

### 3.3 外部依赖

| 服务 | 用途 | Spring 替代 |
|---|---|---|
| OpenWeatherMap OneCall | 天气数据（**数据已由独立爬虫写入数据库**，应用只读） | 仅 JPA 查询 |
| Google Maps Distance Matrix / Geocoding | 行程规划 | `RestClient` + 自实现 retry / Resilience4j |
| DeepSeek（OpenAI 兼容协议） | LLM 对话 | **独立 Python `chat-service`（LangChain）**，Spring 通过 `RestClient` + SSE 代理 |
| SMTP（Flask-Mail） | 邮件 | `spring-boot-starter-mail` (JavaMailSender) |
| PostgreSQL | 持久化 | `spring-boot-starter-data-jpa` + `postgresql` driver |
| 训练好的 scikit-learn `.pkl` 模型 | 自行车预测 | 见 `04-modules.md` §6（推荐：将 ML 模型独立为 Python FastAPI 微服务，Spring Boot 通过 HTTP 调用；备选：导出为 ONNX 用 Java 加载） |

---

## 4. 技术栈映射总览

| 关注点 | Flask | Spring Boot |
|---|---|---|
| Web 框架 | Flask Blueprints | `spring-boot-starter-web` + `@RestController` |
| ORM | SQLAlchemy 2.x | Spring Data JPA + Hibernate |
| 迁移 | Alembic (Flask-Migrate) | Flyway（已采用，`V1__baseline.sql` 为 PostgreSQL DDL） |
| 校验 | Pydantic v2 DTO | `jakarta.validation` (Bean Validation 3) + `@Validated` |
| 类型转换 | Pydantic / 手写 dict | MapStruct `*Mapper`（`componentModel = "spring"`） |
| 配置 | `python-dotenv` + `config.py` | `application.yaml` + `@ConfigurationProperties` |
| 安全 | 手写 JWT (PyJWT) + token_version | `spring-boot-starter-security` + `jjwt` / Spring Security OAuth2 Resource Server |
| 邮件 | Flask-Mail + `ThreadPoolExecutor` | `spring-boot-starter-mail` + `@Async` |
| LLM | LangChain (Python，单体) | **保留 LangChain (Python)** 拆为独立 `chat-service`，Spring 通过 `RestClient`（同步）+ `WebClient` 或 `HttpClient` 转发 SSE |
| 行程外部 API | `googlemaps` Python SDK | `RestClient` 或 OpenFeign |
| ML 模型加载 | `pickle.load` 进程内 | 独立 Python 微服务（推荐）/ ONNX |
| 测试 | pytest + 覆盖率 | JUnit 5 + Mockito + Testcontainers + MockMvc |
| 容器 | Dockerfile + Gunicorn | Dockerfile + `spring-boot-maven-plugin` 分层 |
| 日志 | Python `logging` | SLF4J + Logback |

---

## 5. 阶段路线图（高层）

| Sprint | 主题 | 主要交付 |
|---|---|---|
| **S1** | 工程骨架、配置、数据库连通 | `application.yaml`、`@ConfigurationProperties`、Flyway baseline、`/actuator/health` |
| **S2** | JPA 实体 + 站点 / 天气模块 | Station / Availability / WeatherForecast 实体、对应 Repository、Service、Controller、单元测试 |
| **S3** | 用户系统 + JWT + 邮件 | User 实体、注册/激活/登录/refresh/logout、`token_version` 黑名单、HTML 邮件模板、异步发送 |
| **S4** | 行程规划 | Google Maps Client、Distance Matrix 批量、最小总时长搜索算法（端口现有 Python 逻辑）、Resilience4j 重试 |
| **S5** | LLM 对话 | 部署独立 Python `chat-service`（LangChain + DeepSeek + `message_store` 读写）；Spring `ChatController` + `ChatService` 做 JWT 鉴权、`sessions` 表 ACL、SSE 代理；自动标题生成由 Spring 调用 `chat-service /title` |
| **S6** | ML 预测服务 + 集成 | 部署独立 Python 预测微服务；Spring 端 `PredictionService` 通过 `RestClient` 调用、完整 E2E 联调、Postman/HTTPie 验证 |

每个 Sprint 的细分任务、依赖和验收标准见 `05-testing-and-roadmap.md` §B。

---

## 6. 验收标准（迁移完成的定义）

1. **API 兼容**：原 Postman 集合 `Flask App API.postman_collection.json` 中所有用例对新服务返回的 **HTTP 状态码、响应体结构（`{code, msg, data}` 包裹）、字段命名（snake_case）** 与 Flask 完全一致。
2. **数据兼容**：Spring 端当前目标库为 PostgreSQL；schema 由 Flyway `V1__baseline.sql` 创建并由 Hibernate `ddl-auto=validate` 校验。
3. **行为兼容**：JWT 旧 token 在 `logout` 后立即失效；邮件验证码 5 分钟过期、60 秒重发节流；行程返回 25 (5×5) 候选中的全局最小用时；LLM 首条消息触发标题生成。
4. **测试覆盖率 ≥ 80%**（行覆盖；service / controller 层为重点）。
5. **本地 `docker-compose up` 可一键启动**（应用 + PostgreSQL + 后续 chat-service / prediction-service）。

---

## 7. 不在本次迁移范围

- JCDecaux 数据抓取脚本、天气抓取脚本（Flask 项目中不存在于 `app/` 目录，由外部独立进程负责，数据库中表已存在）。
- 前端 / 移动端。
- ML 模型本身的训练（仅消费已训练产物）。
- `machine_learning/ml.ipynb` 数据分析探索。
