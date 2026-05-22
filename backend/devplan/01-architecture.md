# 01 — 目标架构与包结构

> **路径说明**：本文档中的 `src/main/...` 均指 `backend/src/main/...`。Java 包名 `dev.kaiwen.bikes.*` 不变；Maven 命令在 `backend/` 目录执行。

---

## 1. 技术分层

Flask 单体按 Blueprint 组织路由；Spring Boot 重构采用 **经典分层 + 横切关注点**，与 Flask 的 `{code, msg, data}` 响应契约对齐。

```
┌─────────────────────────────────────────────────────────────────┐
│  controller/     @RestController                                 │
│                  HTTP 入参绑定、@Valid 触发、返回 ApiResponse     │
├─────────────────────────────────────────────────────────────────┤
│  service/        @Service + @Transactional                      │
│                  业务编排、事务边界、外部调用                      │
├─────────────────────────────────────────────────────────────────┤
│  mapper/         MapStruct @Mapper(componentModel = "spring")    │
│                  Entity ↔ DTO/VO 类型转换，无业务副作用            │
├─────────────────────────────────────────────────────────────────┤
│  repository/     JpaRepository 接口                              │
│                  仅数据访问；复杂查询用 @Query / Specification     │
├─────────────────────────────────────────────────────────────────┤
│  model/          @Entity                                         │
│                  与 MySQL 表一一对应（JPA 持久化模型）              │
├─────────────────────────────────────────────────────────────────┤
│  dto/            request/ + response/ + ApiResponse / ApiCodes   │
│                  API 契约层，禁止泄漏 @Entity 到 HTTP 边界          │
├─────────────────────────────────────────────────────────────────┤
│  config/         @Configuration + @ConfigurationProperties       │
│                  Bean 装配、RestClient、线程池、安全（后续）        │
├─────────────────────────────────────────────────────────────────┤
│  exception/      BusinessException / AuthException               │
│                  GlobalExceptionHandler → 统一 ApiResponse 错误体  │
└─────────────────────────────────────────────────────────────────┘
         │
         └── 集成层（在 service/ 或后续 integration/ 中）
              RestClient → Python chat-service / prediction-service
              RestClient → Google Maps
              JavaMailSender → SMTP
```

**依赖方向（只允许向下）：**

`controller → service → repository → model`；`service` 可依赖 `mapper`；`controller/service/mapper` 可依赖 `dto`；`mapper` 可依赖 `model`；`config` / `exception` 为横切，被各层引用，但 **model 不得依赖 dto 或 mapper**。

---

## 2. 关键原则

| 原则 | 说明 | 落地方式 |
|---|---|---|
| **DTO 与 Entity 分离** | HTTP 契约（snake_case JSON）与数据库列映射解耦；前端字段名不变 | `dto/request/*DTO` 入参；`dto/response/*VO` 出参；`model/*` 仅 JPA；`mapper/*Mapper` 用 MapStruct 做类型转换 |
| **配置承载密钥** | API Key、JWT secret、DB 密码不进代码、不写死默认值 | `application-{profile}.yaml` + 环境变量；`@ConfigurationProperties` record；生产 profile 无默认密码 |
| **集中异常处理** | 与 Flask 全局 handler 等价，Controller 不 try-catch 业务错误 | `@RestControllerAdvice` + `ApiCodes` + `ApiResponse.error()` |
| **何时切 feature 包** | 见 §11；当前阶段保持技术分层扁平包 | Sprint S1–S3 用 `dev.kaiwen.bikes.{controller,service,...}`；模块边界稳定后再按业务域拆分 |

---

## 3. 当前包结构（与代码库一致）

截至文档编写时，**已实现** 的 Java 源码树如下（`controller/` 为占位，待 Sprint 接入）：

```
dev.kaiwen.bikes
├── DublinBikesApplication.java
├── controller/                    # （占位）StationController, UserController, ...
├── service/
│   └── StationService, UserService, WeatherService, ...
├── mapper/
│   ├── StationMapper.java
│   ├── UserMapper.java
│   └── WeatherMapper.java
├── repository/                    # 5 个 JpaRepository 接口
│   ├── StationRepository.java
│   ├── AvailabilityRepository.java
│   ├── UserRepository.java
│   ├── WeatherRepository.java
│   └── ChatSessionRepository.java
├── model/                         # 5 个 @Entity
│   ├── Station.java
│   ├── Availability.java
│   ├── User.java
│   ├── WeatherForecast.java
│   └── ChatSession.java
├── dto/
│   ├── ApiResponse.java           # 统一响应包裹 { code, msg, data }
│   ├── ApiCodes.java              # 业务错误码常量
│   ├── request/
│   │   ├── LoginRequestDTO.java
│   │   ├── UserRegistrationRequestDTO.java
│   │   ├── JourneyRequestDTO.java
│   │   ├── ChatRequestDTO.java
│   │   └── ...
│   └── response/
│       ├── StationVO.java
│       ├── UserVO.java
│       ├── AuthTokenVO.java
│       ├── WeatherDataVO.java
│       └── ...
├── config/
│   ├── ChatServiceConfig.java     # RestClient Bean → Python chat-service
│   └── ChatServiceProperties.java
└── exception/
    ├── BusinessException.java
    ├── AuthException.java
    └── GlobalExceptionHandler.java
```

**主类扫描范围（已实现）：**

```java
@SpringBootApplication
@ConfigurationPropertiesScan("dev.kaiwen.bikes.config")
@EntityScan(basePackages = "dev.kaiwen.bikes.model")
@EnableJpaRepositories(basePackages = "dev.kaiwen.bikes.repository")
public class DublinBikesApplication { ... }
```

---

## 4. LLM 对话：Python chat-service，非 LangChain4j

| 层级 | 职责 |
|---|---|
| **Python `chat-service`** | LangChain + 通义 Qwen、`message_store` 读写、同步对话与 SSE 流 |
| **Spring Boot** | JWT 鉴权、`sessions` 表 ACL、HTTP/SSE **代理**到 Python |
| **`ChatServiceConfig`** | 注册 `RestClient` Bean，`baseUrl` 来自 `app.chat-service` |

**不在 JVM 引入** LangChain4j、Spring AI 或任何 Java LLM SDK。`ALIYUN_API_KEY` 等凭证仅存在于 Python 工程 `.env`。

```java
@Configuration
@EnableConfigurationProperties(ChatServiceProperties.class)
public class ChatServiceConfig {

    @Bean
    RestClient chatServiceRestClient(ChatServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }
}
```

后续 `ChatService`（待建）注入该 `RestClient`，负责转发 `/api/chat/*` 到 Python 对应路径。

---

## 5. Profile 与 `application.yaml` 策略

采用 **基础配置 + profile 覆盖**，与 `03-configuration-and-dependencies.md` 一致。

| 文件 | Profile | 职责 |
|---|---|---|
| `application.yaml` | 无（公共） | 应用名、**Jackson snake_case**、JPA `validate`、Flyway、默认 `spring.profiles.active=dev` |
| `application-dev.yaml` | `dev` | 本地 MySQL 数据源（含开发默认账号）、`app.chat-service` 默认 `http://localhost:8002` |
| `application-prod.yaml` | `prod` | 生产数据源（**无默认密码**）、Hikari 池加大、`CHAT_SERVICE_BASE_URL` 必填 |
| `src/test/resources/application.yaml` | `test` | H2 内存库、`flyway.enabled=false`（测试隔离） |

**激活方式：**

- 本地：`SPRING_PROFILES_ACTIVE=dev`（或未设置，沿用 `application.yaml` 默认值）
- 生产/容器：显式 `prod`，全部敏感项走环境变量
- CI 测试：`test` profile

**分层原则：**

- 跨环境不变项 → `application.yaml`（Jackson、Flyway、JPA 策略）
- 环境相关项 → `application-{profile}.yaml`（数据源、外部服务 URL）
- 密钥 → 仅环境变量，禁止提交仓库

---

## 6. 统一响应：`dev.kaiwen.bikes.dto.ApiResponse`

与 Flask 响应体 **字段名与语义一致**：`code == 0` 表示成功，非 0 为业务错误码；`data` 可为 `null`。

```java
package dev.kaiwen.bikes.dto;

public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ApiCodes.SUCCESS, "ok", data);
    }

    public static <T> ApiResponse<T> ok(String msg, T data) {
        return new ApiResponse<>(ApiCodes.SUCCESS, msg, data);
    }

    public static ApiResponse<Void> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
```

**Controller 返回约定：**

- 成功：`return ApiResponse.ok(vo);` 或 `ResponseEntity.ok(ApiResponse.ok(data))`
- 业务失败：抛 `BusinessException` / `AuthException`，由 `GlobalExceptionHandler` 转为 `ApiResponse.error`
- **禁止** 在 Controller 手写 `{code,msg,data}` Map

**`ApiCodes`（已实现片段）：**

| 常量 | 值 | 场景 |
|---|---|---|
| `SUCCESS` | 0 | 成功 |
| `VALIDATION_ERROR` | 40001 | Bean Validation 失败 |
| `AUTH_ERROR` | 40101 | 未登录 / token 无效 |
| `USERNAME_EXISTS` | 40901 | 注册用户名冲突 |
| `EMAIL_EXISTS` | 40902 | 邮箱已注册 |
| `WEATHER_ERROR` | 50001 | 天气数据异常 |
| `GENERIC_ERROR` | 50000 | 未捕获异常 |

---

## 7. 全局异常处理示例

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? "invalid request" : fe.getField() + ": " + fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(ApiCodes.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthException ex) {
        return ResponseEntity.status(401).body(ApiResponse.error(ApiCodes.AUTH_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(
                        ApiCodes.GENERIC_ERROR,
                        "Service temporarily unavailable, please try again later"));
    }
}
```

**`BusinessException` 用法：** Service 层 `throw new BusinessException(ApiCodes.EMAIL_EXISTS, "email already registered", 409)`，由 handler 映射 HTTP 状态与 `code`。

---

## 8. Jackson 配置（snake_case 兼容前端）

在 `application.yaml` 中已配置，保证 Java `camelCase` 字段序列化为 JSON `snake_case`（与 Flask / 前端一致）：

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
    default-property-inclusion: ALWAYS
    serialization:
      write-dates-as-timestamps: false
```

| 配置项 | 作用 |
|---|---|
| `SNAKE_CASE` | `stationNumber` → `station_number` |
| `ALWAYS` | 显式 `null` 字段仍输出（与 Flask 默认行为对齐） |
| `write-dates-as-timestamps: false` | 日期时间 ISO-8601 字符串 |

DTO / VO 使用 Java 命名即可，无需 `@JsonProperty` 逐字段标注（除非个别历史字段例外）。

---

## 9. 类型转换规范（MapStruct）

所有 Entity ↔ DTO/VO 转换统一使用 MapStruct，避免在 Service / Controller 中手写字段搬运代码。

```java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StationMapper {
    StationVO toVO(Station station);
    List<StationVO> toVOList(List<Station> stations);
}
```

约束：
- Mapper 放在 `dev.kaiwen.bikes.mapper`，类名后缀 `*Mapper`。
- Mapper 只做字段映射、格式转换、集合转换；不访问 Repository，不调用外部服务，不承载业务规则。
- 字段名一致时依赖 MapStruct 自动映射；字段名不一致、时间格式、枚举/状态码转换必须显式写 `@Mapping` 或 `default` helper。
- Mapper 采用 `componentModel = "spring"`，由 Spring 注入到 Service。
- `unmappedTargetPolicy = ReportingPolicy.ERROR`，新增 VO 字段时编译期暴露漏映射。
- Controller 不直接调用 Mapper；Controller 调 Service，Service 负责组织业务结果并调用 Mapper。

---

## 10. 命名约定

| 类型 | 包 / 后缀 | 示例 | 说明 |
|---|---|---|---|
| 启动类 | 根包 | `DublinBikesApplication` | 唯一 `@SpringBootApplication` |
| Controller | `controller` + `*Controller` | `StationController` | 路径前缀 `/api/stations` 等与 Flask Blueprint 一致 |
| Service | `service` + `*Service` | `UserService` | 接口可选 `*Service` + `*ServiceImpl`（团队统一即可） |
| 类型转换 | `mapper` + `*Mapper` | `StationMapper` | MapStruct Mapper，Entity ↔ DTO/VO，无业务副作用 |
| Repository | `repository` + `*Repository` | `StationRepository` | `extends JpaRepository<Entity, IdType>` |
| 实体 | `model` + 名词 | `Station`, `ChatSession` | 表名 `@Table(name = "...")` 与现有 MySQL 一致 |
| 请求 DTO | `dto.request` + `*DTO` / `*RequestDTO` | `LoginRequestDTO` | 仅入参；加 `@Valid` |
| 响应 VO | `dto.response` + `*VO` | `StationVO` | 仅出参；不放 JPA 注解 |
| 配置类 | `config` + `*Config` | `ChatServiceConfig` | `@Configuration` Bean 定义 |
| 配置属性 | `config` + `*Properties` | `ChatServiceProperties` | `@ConfigurationProperties` record |
| 业务异常 | `exception` | `BusinessException` | 携带 `code` + HTTP `status` |
| 认证异常 | `exception` | `AuthException` | 固定映射 `ApiCodes.AUTH_ERROR` |
| API 错误码 | `dto.ApiCodes` | `VALIDATION_ERROR` | `public static final int`，禁止魔法数字 |
| 统一响应 | `dto.ApiResponse` | — | 全站唯一响应包裹类型 |
| 数据库迁移 | `db/migration` | `V1__baseline.sql` | Flyway 版本化 SQL |
| 环境配置 | `application-{profile}.yaml` | `dev` / `prod` / `test` | 见 §5 |

**REST 路径：** 保持 Flask 既有前缀（`/api/stations`、`/api/users`、`/api/weather`、`/api/journey`、`/api/chat`），方法名动词化：`listStations`、`registerUser`。

---

## 11. 何时迁移到 feature 包（按业务域）

当前仓库采用 **技术分层扁平包**（§3），适合 Sprint S1–S3 快速落地。满足以下 **任一** 条件时，再评估拆为 feature 包：

| 信号 | 说明 |
|---|---|
| 单包类数量过多 | 例如 `dto/response` 超过 ~30 个类，导航成本明显上升 |
| 跨模块改动频繁 | 一次需求同时改 station + journey + user，扁平 `service/` 难以 code review |
| 团队并行开发冲突 | 多人同时改 `service/`、`dto/` 产生大量 merge conflict |
| 模块可独立测试部署 | 例如 prediction 已完全 HTTP 化，可抽 `prediction/` 子包 |

**目标 feature 结构（迁移后参考，非当前状态）：**

```
dev.kaiwen.bikes
├── common/          # ApiResponse, GlobalExceptionHandler（或保留 dto/ + exception/）
├── station/
├── weather/
├── journey/
├── user/
├── chat/            # ChatController + sessions ACL + RestClient 代理
└── prediction/
```

**迁移步骤建议：**

1. 先补齐扁平包下的 Controller + Service，保证 API 兼容测试通过；
2. 按模块 **整包移动**（Git `git mv`），每移一个模块跑全量测试；
3. `dto` 可随模块迁入 `station/dto/`，或暂时保留顶层 `dto/` 直至全部迁移完成；
4. `repository` / `model` 与模块同包，避免循环依赖；
5. `config` 中跨模块 Bean（如全局 `RestClient`）保留在根 `config/` 或 `common/config/`。

**暂不迁移的情况：** 仅 1–2 人维护、类总数 < 80、无并行 Sprint — 扁平包更简单，符合 YAGNI。

---

## 12. 与相邻文档的关系

| 文档 | 关联内容 |
|---|---|
| `00-overview.md` | 迁移范围、技术栈映射、Sprint 路线图 |
| `02-data-model.md` | `model/` 实体字段与 MySQL 表映射 |
| `03-configuration-and-dependencies.md` | 完整 `application.yaml` 建议、Maven 依赖清单 |
| `04-modules.md` | 各 Controller 端点与 Service 职责 |
| `05-testing-and-roadmap.md` | MockMvc 契约测试、覆盖率目标 |

---

## 13. 检查清单（架构就绪）

- [ ] 新增 API 仅通过 `ApiResponse` + `ApiCodes` 返回
- [ ] Controller 不直接返回 `@Entity`
- [ ] Entity ↔ DTO/VO 转换通过 `mapper/*Mapper`（MapStruct）完成
- [ ] 密钥仅环境变量 + `@ConfigurationProperties`
- [ ] LLM 调用仅经 `RestClient` → Python，不在 JVM 引 LLM SDK
- [ ] `spring.jackson.property-naming-strategy=SNAKE_CASE` 已启用
- [ ] 生产使用 `prod` profile，数据源无硬编码密码
