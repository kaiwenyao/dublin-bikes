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
- `@DataJpaTest`（使用 Testcontainers MySQL，不要用 H2 —— group-by + JSON 列在 H2 行为不同）。
- 验证 `findLatestPerStation()` 正确返回每站点最新一行。
- `WeatherRepository.findTop6...` 边界（恰好 5 / 6 / 7 行）。

**Integration**
- 启动完整 `@SpringBootTest`，Testcontainers MySQL + Flyway baseline。
- 用 `TestRestTemplate` 走全链路：register → send-code → activate → login → me。
- chat 模块：mock `OpenAiStreamingChatModel`，断言 SSE 分块输出和 `[DONE]` 收尾。

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

### Sprint S1 — 工程骨架（2 天）

**目标**：能跑起来，连得上 DB，`/actuator/health` 200。

任务：
1. （手动）追加 `03-configuration-and-dependencies.md` §2 中数据访问、validation、actuator 依赖到 `pom.xml`。
2. 合并 `application.yaml` 配置。
3. 实现 `config/properties/*` 全部 `@ConfigurationProperties` 类。
4. `common/api/ApiResponse`、`common/exception/GlobalExceptionHandler`。
5. Flyway baseline `V1__baseline.sql`（空）。
6. `JpaConfig` + `EntityScan` + 主类 annotations。
7. CI：`mvn verify` + jacoco 报告。

**验收**：`./mvnw spring-boot:run` 启动成功；`curl localhost:5000/actuator/health` 返回 `{"status":"UP"}`。

---

### Sprint S2 — Station + Weather（3 天）

任务：
1. `Station`、`Availability`、`WeatherForecast` Entity。
2. 对应 Repository（含 native query for "latest per station"）。
3. Service / Controller / DTO（StationVO / AvailabilityVO / WeatherDataVO）。
4. WebMvc + Repository 测试覆盖率 ≥ 85%。

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

**验收**：所有 user 端点 Postman 测试通过；logout 后旧 access_token 立即 401。

---

### Sprint S4 — Journey（2 天）

任务：
1. `GoogleMapsClient`（RestClient）+ Resilience4j `@Retry`。
2. `JourneyService.findBestRoute`：复刻 Python 算法（10 → 5 → 5×5）。
3. WebMvc + Mock 测试 ≥ 80% 覆盖。
4. 校验 DTO 二选一（`start_address/end_address` XOR `start/end`）。

**验收**：地址型和坐标型请求各 1 个 Postman 用例对比 Flask 输出，最优路线一致。

---

### Sprint S5 — Chat (LLM)（4 天）

任务：
1. `ChatSession` / `ChatMessage` Entity + Repository（注意 `message_store` JSON 列）。
2. `JdbcChatMemoryStore`：读写 LangChain 兼容 JSON。
3. `QwenChatClient` Bean（OpenAI 兼容协议）。
4. `ChatService.generateChatResponse` 同步 + `generateChatStream` SSE。
5. `_ensureSession` 并发安全（DataIntegrityViolation 重试）。
6. 标题自动生成（异步，异常吞掉）。
7. 会话列表 + 历史接口（行级 ACL：`userId == 当前用户`）。

**验收**：完整 SSE 流式对话端到端跑通；Python 端写入的历史 Java 能读出；Java 端写入的历史 Python 也能读出（双向兼容）。

---

### Sprint S6 — Prediction + 集成验收（3 天）

任务：
1. 独立 Python FastAPI 服务部署（Dockerfile + Compose 集成）。
2. `PredictionClient` + `PredictionService`。
3. `/api/stations/{n}/prediction` 端到端联调。
4. Newman 跑全量 Postman 集合对比 Flask 输出。
5. 完善 Dockerfile（Spring Boot 分层 + JRE）+ `docker-compose.yml`（app + mysql + prediction）。
6. README：开发、测试、部署、回退步骤。

**验收**：`docker-compose up` 一键启动；Postman 全集合 100% pass；覆盖率 ≥ 80%。

---

## C. 风险与决策

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | LangChain Python `SQLChatMessageHistory` JSON 结构演进 | Java/Python 读不到对方写的历史 | 锁版本；用集成测试两端互读 |
| R2 | sklearn 模型 ONNX 化失败 | 无法走方案 B 单 JVM 部署 | 默认走方案 A（Python 微服务） |
| R3 | MySQL `JSON` 列在 H2 行为差异 | 单元测试假阳/假阴 | 仅用 Testcontainers MySQL，不用 H2 |
| R4 | 旧 JWT logout 失效要求"立刻" | 短期内大量旧 token 涌入数据库压力 | 每次校验 `verifyAccessToken` 都 reload user → 加 user 缓存（Caffeine, TTL=30s） |
| R5 | Google Maps quota 用尽 | journey 端点失败 | Resilience4j circuit breaker + fallback 返回 `code:500` 友好提示 |
| R6 | Flask snake_case 字段 vs Spring 默认 camelCase | 前端直接挂掉 | 全局 Jackson `SNAKE_CASE`；DTO 用 camelCase 字段名（Java 习惯），序列化自动转换 |
| R7 | 端口 5000 在 macOS 上被 AirPlay 占用 | 本地启动失败 | 开发文档建议改为 8080 或关闭 AirPlay 接收器；CI 用容器无冲突 |
| R8 | Flask `dt.weekday()` 取值 0..6（周一=0）；Java `DayOfWeek.getValue()` 是 1..7（周一=1） | 预测特征错位 → 模型输出全错 | 文档已注明 `getValue() - 1`；写专门单测断言映射 |
| R9 | Pydantic `UserVO.created_at: str | None` 由 Python `isoformat()` 给出（无时区），Java 端默认 ISO-8601 带 `T` 也无时区 | 字段值表面一致 | 用集成测试 byte-for-byte 对比 |
| R10 | 主类 / 主配置 "暂不能改" 的硬约束 | S1 启动时缺 `@EnableJpaRepositories` 等 | 把这些 annotation 集中在 `config/JpaConfig` 等 `@Configuration` 类，避免改主类即可生效 |

### 关键决策记录（ADR 简版）

**ADR-001 选用 Flyway 而非 Liquibase**
- 决策：Flyway。
- 理由：现状 Alembic 都是顺序 SQL 风格，Flyway 概念一一对应；Liquibase 的 changeSet 概念会让团队不适应。

**ADR-002 LangChain4j 而非 Spring AI**
- 决策：LangChain4j 1.x。
- 理由：与 Python LangChain 概念对齐（ChatMemoryStore、Messages 形态），存量 `message_store` 数据复用最直接；Spring AI 抽象更高但要再写 adapter。

**ADR-003 ML 模型走独立 Python 微服务**
- 决策：FastAPI 子服务。
- 理由：训练栈是 sklearn + pandas，导出 ONNX 风险高；副作用是部署多一个进程。给出 ONNX 备选方案以备未来切换。

**ADR-004 服务端口保持 5000**
- 决策：默认 5000，可由 `SERVER_PORT` 环境变量覆盖。
- 理由：前端、Postman 集合现状写死 `localhost:5000`；零改前端是迁移成功的标志。

**ADR-005 `is_active` 列默认 true、注册时强制 false**
- 决策：复刻 Flask 现状，不"修正"为列默认 false。
- 理由：现存生产数据可能依赖该列默认值（比如外部脚本直接 INSERT），改默认值会回归测试不到的灰盒副作用。

---

## D. 完工 Checklist（迁移结束自查）

- [ ] 现有 `pom.xml` 仅做依赖追加，未删除/降级既有依赖。
- [ ] `application.yaml` 仅做配置追加，未改动 `spring.application.name`。
- [ ] `DublinBikesApplication.java` 仅按需追加 annotation，未改包名。
- [ ] 数据库 schema 无任何 DDL 变更（Flyway 仅 baseline）。
- [ ] 全部 Postman 用例 200 / 4xx / 5xx 与 Flask 一致。
- [ ] JSON 字段名 100% snake_case，值的格式（日期、布尔、null）与 Flask 一致。
- [ ] JWT logout 立即失效（集成测试覆盖）。
- [ ] LangChain `message_store` Python ↔ Java 双向读写兼容。
- [ ] Jacoco 行覆盖 ≥ 80%。
- [ ] Docker Compose 一键启动通过。
- [ ] README 含开发 / 测试 / 部署 / 回退步骤。
- [ ] 所有外部 API key 通过环境变量注入；仓库无明文密钥。
