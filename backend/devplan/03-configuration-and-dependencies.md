# 03 — 配置与依赖

> 包结构见 `01-architecture.md`。配置按 profile 拆分：`application.yaml` + `application-dev.yaml` / `application-prod.yaml`。
> 下文保留依赖清单与 `app.*` 配置属性参考。

---

## 1. 现状（已存在，不改动）

### 1.1 `pom.xml` 当前 GAV
- parent: `org.springframework.boot:spring-boot-starter-parent:3.5.14`
- artifact: `dev.kaiwen:bikes:0.0.1-SNAPSHOT`
- `java.version = 21`

### 1.2 已存在依赖
| GroupId | ArtifactId | Scope |
|---|---|---|
| org.springframework.boot | spring-boot-starter | — |
| org.springframework.boot | spring-boot-starter-web | — |
| org.springframework.boot | spring-boot-starter-test | test |
| org.projectlombok | lombok | optional |

### 1.3 已存在配置（profile 拆分）

| 文件 | 作用 |
|---|---|
| `application.yaml` | 应用名、Jackson、Flyway、JPA `validate`、默认 `spring.profiles.active=dev` |
| `application-dev.yaml` | 本地 PostgreSQL 数据源、`app.chat-service` 默认值 |
| `application-prod.yaml` | 生产数据源（无默认密码）、连接池调大 |
| `application-test`（`src/test/resources/application.yaml`） | H2 内存库、`flyway.enabled=false` |

---

## 2. 建议追加的 Maven 依赖（手动决定何时加入）

### 2.1 数据访问与迁移（Sprint S1 之前）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

### 2.2 校验 + Actuator（S1）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 2.2.1 类型转换：MapStruct（S1）

Entity ↔ DTO/VO 转换统一使用 MapStruct。Service 注入 `*Mapper`，不要在 Service / Controller 中手写字段搬运代码。

```xml
<properties>
    <mapstruct.version>1.6.3</mapstruct.version>
    <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
</properties>

<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
</dependency>
```

同时配置 annotation processor（若项目继续使用 Lombok，保留 Lombok processor 和 binding）：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <generatedSourcesDirectory>${project.build.directory}/generated-sources/mapstruct</generatedSourcesDirectory>
        <annotationProcessorPaths>
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>${mapstruct.version}</version>
            </path>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok-mapstruct-binding</artifactId>
                <version>${lombok-mapstruct-binding.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

> `lombok.version` 通常由 Spring Boot parent 托管；如果 IDE/Maven 报 property 不存在，则在 `<properties>` 中显式补上当前 Lombok 版本。
> `componentModel = "spring"` 与 `unmappedTargetPolicy = ReportingPolicy.ERROR` 写在各 `@Mapper` 上；`generatedSourcesDirectory` 避免 IDE 对默认 `target/generated-sources/annotations` 目录做错误的二次编译。

### 2.2.2 用户缓存（S3，可选）

`JwtAuthenticationFilter` 每次都 `reload user` 校验 `token_version`，QPS 高时会压数据库（见 `05-testing-and-roadmap.md` R4），可引入短 TTL 缓存：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

> Caffeine 版本由 Spring Boot 3.5 BOM 托管，无需写 `<version>`。建议 TTL ≤ 30s，保证 `logout` 后旧 token 失效延迟可控。

### 2.3 安全 + JWT（S3）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### 2.4 邮件 + 模板（S3）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```
> Thymeleaf 用于复刻 Flask 的 `email_verification.html` 模板，把 `{code}` / `{expires_minutes}` / `{activation_link_section}` 占位符转成 Thymeleaf 表达式。

### 2.5 外部 HTTP 调用（S4 / S6）
```xml
<!-- Spring 6 内置 RestClient，无需额外依赖；如需熔断/重试： -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

### 2.6 LLM（S5）

> **决策**：LLM 调用走 **独立 Python `chat-service`**（LangChain + Qwen），Spring 端 **不引入任何 Java LLM SDK**（不需要 LangChain4j、Spring AI）。
>
> 详见 ADR-002（`05-testing-and-roadmap.md` §C）。

Spring 端依赖：
```xml
<!-- 同步调用 chat-service（沿用 §2.5 的 RestClient，无新依赖） -->
<!-- SSE 转发：用 Spring 6 内置 RestClient + SseEmitter 或 WebClient + WebFlux -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
    <!-- 仅当选择 WebClient 转发 SSE 时引入；用 JDK HttpClient + SseEmitter 则不需要 -->
</dependency>
```

Python `chat-service` 依赖（独立工程，不在 `backend/pom.xml`）：
- `fastapi`, `uvicorn[standard]`
- `langchain`, `langchain-community`（提供 `SQLChatMessageHistory`）
- `langchain-openai`（Qwen 走 OpenAI 兼容协议）
- `sqlalchemy`, `pymysql` 或 `mysqlclient`
- `sse-starlette`（SSE 响应）

详细服务规格见 `04-modules.md` §5.7。

### 2.7 测试
```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```
> Spring Boot 3.5 BOM 已托管 Testcontainers 版本，无需写 `<version>`。

---

## 3. 建议 `application.yaml`（合并到现有文件，**保留** `spring.application.name`）

```yaml
spring:
  application:
    name: dublin-bikes

  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/dublinbikes}
    username: ${DATABASE_USERNAME:postgres}
    password: ${DATABASE_PASSWORD:}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 5000

  jpa:
    hibernate:
      ddl-auto: validate          # 严禁 update / create
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        jdbc.time_zone: UTC
    show-sql: false

  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1
    locations: classpath:db/migration

  jackson:
    property-naming-strategy: SNAKE_CASE
    default-property-inclusion: ALWAYS
    serialization:
      write-dates-as-timestamps: false

  mail:
    host: ${MAIL_SERVER:}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: ${MAIL_USE_TLS:true}
      mail.smtp.ssl.enable: ${MAIL_USE_SSL:false}

  task:
    execution:
      pool:
        core-size: 2
        max-size: 2
        queue-capacity: 100
      thread-name-prefix: email-send-

server:
  port: ${SERVER_PORT:5000}    # 与 Flask 默认端口一致，便于零改前端联调；macOS AirPlay 占用时可用 SERVER_PORT 覆盖
  servlet:
    encoding:
      charset: UTF-8
      force: true

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics
  endpoint:
    health:
      show-details: when_authorized

logging:
  level:
    dev.kaiwen.bikes: INFO
    org.springframework.web: INFO
    org.hibernate.SQL: WARN

# ---- 业务自定义配置（对应 @ConfigurationProperties） ----

app:
  jwt:
    access-secret: ${JWT_SECRET_KEY}
    refresh-secret: ${JWT_REFRESH_SECRET_KEY}
    access-expires-seconds: ${JWT_ACCESS_EXPIRES_SECONDS:900}     # 15min
    refresh-expires-seconds: ${JWT_REFRESH_EXPIRES_SECONDS:604800} # 7d

  verification:
    code-expire-seconds: ${VERIFICATION_CODE_EXPIRE_SECONDS:300}
    resend-cooldown-seconds: ${VERIFICATION_CODE_RESEND_COOLDOWN_SECONDS:60}

  mail:
    from: ${MAIL_FROM:}
    from-name: ${MAIL_DEFAULT_FROM_NAME:Dublin Bikes}
    frontend-base-url: ${FRONTEND_BASE_URL:http://localhost:5173}

  google-maps:
    api-key: ${GOOGLE_MAPS_API_KEY:}
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
    max-retries: 2

  openweather:
    base-url: ${OPENWEATHER_API_BASE_URL:https://api.openweathermap.org/data/3.0/onecall}
    api-key: ${OPENWEATHER_API_KEY:}   # 仅占位，运行时不再调用外部接口

  chat-service:
    base-url: ${CHAT_SERVICE_BASE_URL:http://localhost:8002}
    connect-timeout-ms: 3000
    read-timeout-ms: 30000          # 同步对话：单次响应可能 ~20s
    stream-timeout-ms: 120000       # SSE：长连接
    title-timeout-ms: 8000          # 标题生成

  prediction:
    enabled: ${PREDICTION_ENABLED:true}
    base-url: ${PREDICTION_BASE_URL:http://localhost:8001}
    timeout-ms: 3000
```

> **Qwen API Key 不再注入到 Spring 端**：`ALIYUN_API_KEY`、模型选择、Qwen base-url 等迁移至 Python `chat-service` 工程的 `.env`（Spring `application.yaml` 不再持有 LLM 凭证）。

### 3.1 `@ConfigurationProperties` 类示例

```java
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String accessSecret,
    String refreshSecret,
    long accessExpiresSeconds,
    long refreshExpiresSeconds
) {}
```

主类当前状态见 `01-architecture.md` §3（已实现）：

```java
@SpringBootApplication
@ConfigurationPropertiesScan("dev.kaiwen.bikes.config")
@EntityScan(basePackages = "dev.kaiwen.bikes.model")
@EnableJpaRepositories(basePackages = "dev.kaiwen.bikes.repository")
public class DublinBikesApplication { ... }
```

S3（邮件异步）启动前需追加 `@EnableAsync`；其余 annotation 不动。`@ConfigurationProperties` 类统一放在 `dev.kaiwen.bikes.config`（与 `01-architecture.md` §10 命名约定一致），**不**再建 `config.properties` 子包。

---

## 4. 环境变量来源

直接复用 Flask 项目 `.env.example` 的所有键（见 `flask-app/.env.example`）；唯一不同：

- `DATABASE_URL` 使用 PostgreSQL JDBC URL（本地或 Supabase 等托管实例）：
  - 示例：`jdbc:postgresql://localhost:5432/dublinbikes`
  - 托管（SSL）：`jdbc:postgresql://host:5432/postgres?sslmode=require`
  - **建议拆分**：`DATABASE_URL`（仅含 host/db）+ `DATABASE_USERNAME` + `DATABASE_PASSWORD`，避免把 password 写在 URL 里。

---

## 5. 配置加载流程

```
.env / OS env
   │
   ▼
application.yaml  ──► @ConfigurationProperties POJOs
                                │
                                ▼
                          @Service / @Configuration 注入
```

- 本地开发用 `.env` + spring-dotenv（**可选**：`me.paulschwarz:spring-dotenv:4.0.0`）；
- CI / 生产用环境变量直接注入；
- 敏感字段（`api-key`, `secret`）**不允许** 提交到 `application.yaml` 默认值。
