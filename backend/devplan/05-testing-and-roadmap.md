# 05 — 测试、迁移路线图、风险与决策

---

## A. 测试策略

### A.1 覆盖率目标

- **行覆盖 ≥ 80%**（Jacoco `mvn verify` 强制）
- 关键路径分支覆盖 ≥ 70%（auth、journey、chat session 维护）

### A.2 测试金字塔

```
              ┌──────────────┐
              │   E2E (5%)   │ Postman 集合 / Newman 回归
              └──────────────┘
            ┌────────────────────┐
            │  Integration (25%) │ @SpringBootTest + Testcontainers
            └────────────────────┘
        ┌────────────────────────────┐
        │     Unit Tests (70%)       │ JUnit 5 + Mockito
        └────────────────────────────┘
```

### A.3 各层测试要点

**Unit (Service / Util)**
- `DistanceUtils.haversineKm` — 端口 `tests/test_utils.py` 的所有断言（包括坐标相同时的浮点稳定性）。
- `JwtService` — 生成、解析、`ver` 不匹配拒绝、`exp` 过期拒绝。
- `ChatService.generateSessionId` — 三种分支：合法短 chatId、超长 chatId、非法字符 chatId。
- `JourneyService.findBestRoute` — mock `GoogleMapsClient`，断言 5×5 全局最小算法返回。

**Slice / WebMvc**
- 每个 Controller 用 `@WebMvcTest`，mock Service，断言 HTTP 状态、JSON 字段、错误码包裹。
- 用 `MockMvc` + `jsonPath("$.code").value(0)` 等。

**JPA Repository**
- 当前测试 profile 使用 H2 PostgreSQL mode、`ddl-auto=create-drop`、`flyway.enabled=false`，适合轻量 Repository smoke test。
- 对 `findLatestPerStation()`、Flyway DDL、`message_store.message JSONB` 等 PostgreSQL 特性，后续应追加 Testcontainers PostgreSQL 集成测试。
- 验证 `findLatestPerStation()` 正确返回每站点最新一行。
- `WeatherRepository.findTop6...` 边界（恰好 5 / 6 / 7 行）。

**Integration**
- 启动完整 `@SpringBootTest`，优先用 Testcontainers PostgreSQL + Flyway baseline 覆盖真实 DDL；轻量场景可继续使用当前 H2 test profile。
- 用 `TestRestTemplate` 走全链路：register → send-code → activate → login → me。
- chat 模块：`WireMock` 模拟 Python `chat-service` 的 `/chat/stream`，断言 Spring SSE 透传分块输出和 `[DONE]` 收尾。

**外部 API**
- `WireMock` 模拟 Google Maps Distance Matrix；测试 5×5 矩阵解析与重试。
- `WireMock` 模拟 Prediction FastAPI；断言特征行字段名、字段顺序与训练一致。

**E2E**
- 用 Newman 跑现有 `Flask App API.postman_collection.json` 对新服务回归；做差异 diff，**任何字段名/顺序变化都视为退化**。

### A.4 测试数据

- 用 `@Sql` 注解或 `TestData` builder 注入；不要依赖生产快照。
- 时间断言：所有断言用 `Clock` 注入，单元测试 stub `Clock.fixed(...)`，避免抖动。

---

## B. Sprint 路线图

### Sprint S1 — 工程骨架（已基本落地）

**目标**：能跑起来，连得上 DB，`/actuator/health` 200。

任务：
1. 已追加数据访问、validation、Flyway、MapStruct 依赖；Actuator 尚未加入。
2. 已配置 `application.yaml`、`application-dev.yaml`、`application-prod.yaml`、test profile。
3. 已实现 `ChatServiceProperties`；JWT / Mail / Google Maps / Prediction 的 properties 尚未实现。
4. 已实现 `dto/ApiResponse`、`ApiCodes`、`exception/GlobalExceptionHandler`。
5. 已实现完整 PostgreSQL `V1__baseline.sql`，不是空 baseline。
6. 已在主类添加 `@ConfigurationPropertiesScan`、`@EntityScan`、`@EnableJpaRepositories`。
7. CI / Jacoco 尚未落地。

**验收**：当前目标是 `mvn test` 通过。`/actuator/health` 需要先加入 Actuator 依赖后才能作为验收项。

---

### Sprint S2 — Station + Weather（3 天）

任务：
1. `Station`、`Availability`、`WeatherForecast` Entity（已完成）。
2. 对应 Repository（含 native query for "latest per station"）（已完成）。
3. DTO 已完成；`StationMapper` 已覆盖 `StationVO` / `AvailabilityVO`。Weather 仅 VO 已完成，`WeatherMapper`、Service、Controller 尚未完成。
4. Service / Controller / WebMvc 测试待补。

**验收**：四个 GET 端点（不含 prediction）能用现有 Postman 用例打通，响应字段顺序、命名一致。

---

### Sprint S3 — User Auth + Mail（4 天）

任务：
1. `User` Entity、Repository、Service。
2. `JwtService`（双密钥、`type/ver`）+ `JwtAuthenticationFilter` + `SecurityConfig`。
3. 验证码生成、节流、激活码 + token 双通道。
4. `BCryptPasswordEncoder`。
5. `MailConfig` + Thymeleaf 模板 + `@Async` 发送。
6. 7 个 user 端点 + `/me`。
7. 端到端测试：register → send-code → activate → login → me → refresh → logout 后旧 token 拒绝。

**验收**：所有 user 端点 Postman 测试通过；login / refresh 返回 `access_token`、`refresh_token`、`expires_in`、`token_type`；logout 后旧 access_token 立即 401。

---

### Sprint S4 — Journey（2 天）

任务：
1. `GoogleMapsClient`（RestClient）+ Resilience4j `@Retry`。
2. `JourneyService.findBestRoute`：复刻 Python 算法（10 → 5 → 5×5）。
3. WebMvc + Mock 测试 ≥ 80% 覆盖。
4. 校验 DTO 二选一（`start_address/end_address` XOR `start/end`）。

**验收**：地址型和坐标型请求各 1 个 Postman 用例对比 Flask 输出，最优路线一致。注意当前 `JourneyResponseDTO` 仍是扁平结构，若前端需要 `route_info` 与 `search_context`，实现 Service/Controller 前需先调整 DTO。

---

### Sprint S5 — Chat (LLM)（4 天）

> Spring 端不再实现 LLM 调用与 `message_store` 读写；这两块由独立 Python `chat-service` 完成。Spring 仅做鉴权、`sessions` 表 ACL、SSE 透传。

任务：
1. **Python `chat-service`** 工程化：FastAPI + LangChain（`SQLChatMessageHistory`）+ DeepSeek（OpenAI 兼容），端点 `/chat`、`/chat/stream`、`/chat/title`、`/sessions/{id}/messages`、`/health`。Dockerfile + `docker-compose` 服务定义。详见 `04-modules.md` §5.7。
2. Spring `ChatSession` Entity + `ChatSessionRepository`（仅 `sessions` 表，不映射 `message_store`）。
3. Spring `ChatServiceClient`（`RestClient` 同步 + `WebClient`/JDK `HttpClient` SSE 转发）+ `ChatServiceProperties`。
4. Spring `ChatService.generateChatResponse` 同步 + `generateChatStream` SSE 透传。
5. `_ensureSession` 并发安全（DataIntegrityViolation 重试）。
6. 标题自动生成：`@Async` 调用 `chat-service /chat/title`，异常吞掉。
7. 会话列表 + 历史接口（行级 ACL：`userId == 当前用户`，命中后再代理到 chat-service 取历史）。

**验收**：完整 SSE 流式对话端到端跑通；`docker-compose up` 同时拉起 Spring + chat-service + PostgreSQL；session ACL 测试覆盖（A 用户无法读到 B 用户的 session）。

---

### Sprint S6 — Prediction + 集成验收（3 天）

任务：
1. 独立 Python FastAPI 服务部署（Dockerfile + Compose 集成）。
2. `PredictionClient` + `PredictionService`。
3. `/api/stations/{n}/prediction` 端到端联调。
4. Newman 跑全量 Postman 集合对比 Flask 输出。
5. 完善 Dockerfile（Spring Boot 分层 + JRE）+ `docker-compose.yml`（app + PostgreSQL + chat-service + prediction，沿用 S5 已加入的 chat-service 服务）。
6. README：开发、测试、部署、回退步骤。

**验收**：`docker-compose up` 一键启动；Postman 全集合 100% pass；覆盖率 ≥ 80%。

---

## C. 风险与决策

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | LangChain Python `SQLChatMessageHistory` JSON 结构演进 | 升级 LangChain 后历史读不出 | 锁定 `langchain-community` 版本；chat-service 升级前跑 `/sessions/{id}/messages` 回归测试 |
| R2 | sklearn 模型 ONNX 化失败 | 无法走方案 B 单 JVM 部署 | 默认走方案 A（Python 微服务） |
| R3 | PostgreSQL `JSONB` / native SQL 与 H2 行为差异 | 单元测试假阳/假阴 | 轻量测试用 H2，DDL/native query 回归用 Testcontainers PostgreSQL |
| R4 | 旧 JWT logout 失效要求"立刻" | 短期内大量旧 token 涌入数据库压力 | 每次校验 `verifyAccessToken` 都 reload user → 加 user 缓存（Caffeine, TTL=30s） |
| R5 | Google Maps quota 用尽 | journey 端点失败 | Resilience4j circuit breaker + fallback 返回 `code:500` 友好提示 |
| R6 | Flask snake_case 字段 vs Spring 默认 camelCase | 前端直接挂掉 | 全局 Jackson `SNAKE_CASE`；DTO 用 camelCase 字段名（Java 习惯），序列化自动转换 |
| R7 | 端口 5000 在 macOS 上被 AirPlay 占用 | 本地启动失败 | 默认端口已改为 **8080**（`application.yaml`）；仍可用 `SERVER_PORT` 覆盖；CI 用容器无冲突 |
| R8 | Flask `dt.weekday()` 取值 0..6（周一=0）；Java `DayOfWeek.getValue()` 是 1..7（周一=1） | 预测特征错位 → 模型输出全错 | 文档已注明 `getValue() - 1`；写专门单测断言映射 |
| R9 | Pydantic `UserVO.created_at: str | None` 由 Python `isoformat()` 给出（无时区），Java 端默认 ISO-8601 带 `T` 也无时区 | 字段值表面一致 | 用集成测试 byte-for-byte 对比 |
| R10 | 主类 / 主配置 "暂不能改" 的硬约束 | S1 启动时缺 `@EnableJpaRepositories` 等 | 把这些 annotation 集中在 `config/JpaConfig` 等 `@Configuration` 类，避免改主类即可生效 |

### 关键决策记录（ADR 简版）

**ADR-001 选用 Flyway 而非 Liquibase**
- 决策：Flyway。
- 理由：现状 Alembic 都是顺序 SQL 风格，Flyway 概念一一对应；Liquibase 的 changeSet 概念会让团队不适应。

**ADR-002 LLM 走独立 Python `chat-service`（而非 Java SDK）**
- 决策：保留 Python LangChain 实现，拆为独立 FastAPI 微服务 `chat-service`；Spring 端不引入 LangChain4j、Spring AI。
- 理由：
  1. LangChain Python 是 LangChain 的"母生态"，`SQLChatMessageHistory` 行为、`message_store` JSON 结构由它定义；保留 Python 端，避免任何跨语言 JSON 兼容性翻译。
  2. Flask 项目原本就是 LangChain Python 实现，迁移工作量近乎 0（只把 `chat_service.py` 抽成 FastAPI app）。
  3. 与 ADR-003（Prediction 走 Python 微服务）一致：Spring 是 HTTP/ACL/事务边界，Python 是 ML/LLM 计算边界，职责清晰。
  4. LLM 凭证 / 模型选择 / 限流 / 提示词调优都收敛在 Python 端，Spring 端不背 LLM SDK 升级负担。
- 代价：多一个进程；多一跳网络（同 Compose 网络，可忽略）。

**ADR-003 ML 模型走独立 Python 微服务**
- 决策：FastAPI 子服务。
- 理由：训练栈是 sklearn + pandas，导出 ONNX 风险高；副作用是部署多一个进程。给出 ONNX 备选方案以备未来切换。

**ADR-004 服务端口默认 8080**
- 决策：默认 **8080**，可由 `SERVER_PORT` 环境变量覆盖。
- 理由：macOS 上 5000 常被 AirPlay Receiver 占用导致本地 `spring-boot:run` 失败；前端 Vite 代理、`BACKEND_PORT`、Postman `baseUrl` 已对齐 `localhost:8080`。

**ADR-005 `is_active` 列默认 true、注册时强制 false**
- 决策：复刻 Flask 现状，不"修正"为列默认 false。
- 理由：现存生产数据可能依赖该列默认值（比如外部脚本直接 INSERT），改默认值会回归测试不到的灰盒副作用。

**ADR-006 Python `chat-service` Web 框架选用 FastAPI**
- 决策：裸 **FastAPI + `sse-starlette`**，不使用 Flask、不使用 LangServe。
- 理由：
  1. 原生 `async/await` 与 LangChain `astream`、`ainvoke` 零摩擦；Flask 的 sync WSGI 模型在流式输出时需要额外 worker/线程绕路。
  2. `sse-starlette.EventSourceResponse` 一行起 SSE，且能严格控制事件体格式 —— 必须输出 `data: {"content":"..."}\n\n` … `data: [DONE]\n\n` 才能让 Spring 端透传给现有前端零改动。
  3. Pydantic 模型直接给出 `/openapi.json`，方便 Spring `ChatServiceClient` 对齐 DTO 字段。
  4. LangServe 虽然是 LangChain 官方 FastAPI 适配器，但它把端点路径和事件协议都框死（`/invoke`、`/stream` + 自有事件 schema），与本项目自定义 `data: {"content":...}` 契约不兼容；放弃。
  5. Django / Litestar 在本场景（4 个端点的薄微服务）属于过度选型。
- 代价：团队需熟悉 async Python；好在 chat-service 本身只有 ~150 行，学习成本可控。

---

## D. 完工 Checklist（迁移结束自查）

- [ ] 现有 `pom.xml` 仅做依赖追加，未删除/降级既有依赖。
- [ ] `application.yaml` 仅做配置追加，未改动 `spring.application.name`。
- [ ] `DublinBikesApplication.java` 仅按需追加 annotation，未改包名。
- [ ] 数据库 schema 无任何 DDL 变更（Flyway 仅 baseline）。
- [ ] 全部 Postman 用例 200 / 4xx / 5xx 与 Flask 一致。
- [ ] JSON 字段名 100% snake_case，值的格式（日期、布尔、null）与 Flask 一致。
- [ ] JWT logout 立即失效（集成测试覆盖）。
- [ ] Python `chat-service` 容器健康；Spring `ChatServiceClient` 同步 + SSE 两端用例均通过；`message_store` 由 Python 单向写入。
- [ ] Jacoco 行覆盖 ≥ 80%。
- [ ] Docker Compose 一键启动通过。
- [ ] README 含开发 / 测试 / 部署 / 回退步骤。
- [ ] 所有外部 API key 通过环境变量注入；仓库无明文密钥。
