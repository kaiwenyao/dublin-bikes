# 01 — 目标架构与包结构

## 1. 分层

```
┌──────────────────────────────────────────┐
│  Controller  (@RestController)            │ — HTTP I/O, DTO ⇆ VO, 全局异常映射
├──────────────────────────────────────────┤
│  Service     (@Service)                   │ — 业务逻辑、事务边界
├──────────────────────────────────────────┤
│  Repository  (extends JpaRepository)      │ — JPA 数据访问
├──────────────────────────────────────────┤
│  Entity      (@Entity)                    │ — 持久化模型
└──────────────────────────────────────────┘
       │
       └──── Integration (RestClient / Mail / LLM)
```

- Controller **只做** 参数绑定、校验触发、调用 Service、响应封装。
- Service **拥有**事务（`@Transactional`），抛业务异常，不直接处理 HTTP。
- Repository 只做查询；复杂查询用 `@Query` 或 Specification。

## 2. 包结构（在已有 `dev.kaiwen.bikes` 基础上扩展）

```
dev.kaiwen.bikes
├── DublinBikesApplication.java              (已存在)
├── common/
│   ├── api/
│   │   ├── ApiResponse.java                 // 统一 {code, msg, data} 包裹
│   │   └── ApiCodes.java                    // 错误码常量（与 Flask 对齐）
│   ├── exception/
│   │   ├── BusinessException.java
│   │   ├── AuthException.java
│   │   └── GlobalExceptionHandler.java      // @RestControllerAdvice
│   └── util/
│       ├── DistanceUtils.java               // Haversine
│       └── RetryUtils.java                  // Gmaps 重试（或 Resilience4j 配置）
├── config/
│   ├── JpaConfig.java
│   ├── AsyncConfig.java                     // @EnableAsync + ThreadPoolTaskExecutor
│   ├── MailConfig.java
│   ├── SecurityConfig.java
│   ├── RestClientConfig.java
│   └── properties/                          // @ConfigurationProperties
│       ├── JwtProperties.java
│       ├── MailProperties.java
│       ├── GoogleMapsProperties.java
│       ├── OpenWeatherProperties.java
│       ├── AliyunQwenProperties.java
│       ├── VerificationProperties.java
│       └── PredictionProperties.java
├── station/
│   ├── StationController.java
│   ├── StationService.java
│   ├── StationRepository.java
│   ├── AvailabilityRepository.java
│   ├── domain/
│   │   ├── Station.java                     // @Entity
│   │   └── Availability.java                // @Entity
│   └── dto/
│       ├── StationVO.java
│       └── AvailabilityVO.java
├── weather/
│   ├── WeatherController.java
│   ├── WeatherService.java
│   ├── WeatherRepository.java
│   ├── domain/WeatherForecast.java
│   └── dto/WeatherDataVO.java
├── journey/
│   ├── JourneyController.java
│   ├── JourneyService.java
│   ├── integration/GoogleMapsClient.java
│   └── dto/
│       ├── JourneyRequest.java              // start_address/end_address OR start/end coords
│       └── JourneyResponse.java
├── user/
│   ├── UserController.java
│   ├── UserService.java
│   ├── auth/
│   │   ├── JwtService.java                  // 双密钥 access/refresh + token_version
│   │   ├── JwtAuthenticationFilter.java
│   │   └── PasswordEncoderConfig.java       // BCrypt
│   ├── mail/
│   │   └── VerificationMailService.java     // @Async send
│   ├── domain/User.java
│   ├── UserRepository.java
│   └── dto/
│       ├── UserRegistrationRequestDTO.java
│       ├── LoginRequestDTO.java
│       ├── RefreshTokenRequestDTO.java
│       ├── ActivateRequestDTO.java
│       ├── ActivateByTokenRequestDTO.java
│       ├── SendVerificationCodeRequestDTO.java
│       ├── UserVO.java
│       └── AuthTokenVO.java
├── prediction/
│   ├── PredictionService.java
│   ├── integration/PredictionClient.java    // 调用 Python FastAPI
│   └── dto/PredictionPointVO.java
└── chat/
    ├── ChatController.java
    ├── ChatService.java
    ├── SessionService.java
    ├── integration/QwenChatClient.java      // LangChain4j ChatLanguageModel
    ├── memory/JdbcChatMemoryStore.java      // 读写 message_store
    ├── domain/
    │   ├── ChatSession.java                 // 表 sessions
    │   └── ChatMessage.java                 // 表 message_store
    ├── ChatSessionRepository.java
    ├── ChatMessageRepository.java
    └── dto/
        ├── ChatRequest.java
        ├── ChatReplyVO.java
        ├── ChatSessionVO.java
        └── ChatMessageVO.java
```

> 站点预测接口 `/api/stations/{number}/prediction` 仍由 `StationController` 暴露，内部委托给 `PredictionService` —— 路径与 Flask 保持一致。

## 3. 统一响应封装

Flask 现状（所有业务接口）：
```json
{ "code": 0, "msg": "ok", "data": { ... } }
```

Spring 端 DTO（**字段命名保持 snake_case，使用 Jackson `PropertyNamingStrategies.SNAKE_CASE` 全局策略**）：

```java
package dev.kaiwen.bikes.common.api;

public record ApiResponse<T>(int code, String msg, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "ok", data); }
    public static <T> ApiResponse<T> ok(String msg, T data) { return new ApiResponse<>(0, msg, data); }
    public static ApiResponse<Void> error(int code, String msg) { return new ApiResponse<>(code, msg, null); }
}
```

错误码必须保持原值，与 Flask 对齐：

| code | 含义 | 出现位置 |
|---|---|---|
| 0 | 成功 | 所有 200/201 |
| 40001 | 请求体/参数校验失败 | 所有 DTO 校验失败 |
| 40101 | 认证失败 / token 无效或过期 | user / chat |
| 40901 | 用户名已存在 | register |
| 40902 | 邮箱已存在 | register |
| 40903 | 用户冲突（其他） | register |
| 50001 | 天气服务错误 | weather |
| 50000 | 通用 500 | chat 等 |
| 400/404/500 | 行程模块沿用 HTTP 数字本身 | journey |

## 4. 全局异常处理

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? "invalid request"
            : fe.getField() + ": " + fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, msg));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthException ex) {
        return ResponseEntity.status(401)
                .body(ApiResponse.error(40101, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(50000, "Service temporarily unavailable, please try again later"));
    }
}
```

## 5. Jackson 配置（保持 snake_case 兼容）

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
    default-property-inclusion: ALWAYS   # 与 Flask 的 None → null 行为一致
    serialization:
      write-dates-as-timestamps: false   # 输出 ISO-8601，与 Python isoformat() 对齐
```

## 6. 命名约定

| 类别 | 规则 | 例 |
|---|---|---|
| Entity | 单数名词 | `Station`, `Availability` |
| Repository | `<Entity>Repository` | `StationRepository` |
| Service | `<Domain>Service` | `JourneyService` |
| DTO 请求 | `<Verb><Domain>RequestDTO` | `UserRegistrationRequestDTO` |
| VO 响应 | `<Domain>VO` | `StationVO`, `AuthTokenVO` |
| Controller | `<Domain>Controller` | `WeatherController` |
| 配置属性 | `<Name>Properties` + `@ConfigurationProperties(prefix="...")` | `JwtProperties` |
| URL | kebab-case + 复数（保持与 Flask 完全一致） | `/api/users/send-verification-code` |
| JSON 字段 | snake_case | `available_bikes`, `refresh_token` |

## 7. 日志

- 框架：SLF4J + Logback（Spring Boot 默认）。
- 业务日志统一打印 `traceId`（可后续接入 Micrometer Tracing），格式：
  ```
  %d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{traceId:-}] %logger{36} - %msg%n
  ```
- 邮件、行程外部 API、LLM 调用 → `INFO` 级，含耗时；异常 → `ERROR` 级带堆栈。
