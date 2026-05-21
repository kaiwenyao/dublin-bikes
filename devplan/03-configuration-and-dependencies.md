# 03 — 配置与依赖

> 严格约束：**不修改现有 `pom.xml` 与 `application.yaml`**。本文件给出 **建议** 的 Maven 依赖清单和最终 `application.yaml` 形态，作为后续手工合并参考。

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

### 1.3 已存在配置
`src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: dublin-bikes
```

---

## 2. 建议追加的 Maven 依赖（手动决定何时加入）

### 2.1 数据访问与迁移（Sprint S1 之前）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
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

**方案 A（推荐）：LangChain4j**
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
<dependency>
    <!-- 阿里云通义 Qwen 兼容 OpenAI 协议 -->
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

**方案 B：Spring AI（OpenAI 兼容）**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```
选其一，不要同时引入。

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
    url: ${DATABASE_URL:jdbc:mysql://localhost:3306/dublinbikes?useUnicode=true&characterEncoding=utf-8&serverTimezone=UTC}
    username: ${DATABASE_USERNAME:root}
    password: ${DATABASE_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
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
        dialect: org.hibernate.dialect.MySQLDialect
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
        max-size: 4
        queue-capacity: 100
      thread-name-prefix: email-send-

server:
  port: 5000             # 与 Flask 默认端口一致，便于零改前端联调
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

  aliyun-qwen:
    api-key: ${ALIYUN_API_KEY}
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    chat-model: qwen-plus
    title-model: qwen-plus

  prediction:
    enabled: ${PREDICTION_ENABLED:true}
    base-url: ${PREDICTION_BASE_URL:http://localhost:8001}
    timeout-ms: 3000
```

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

主类建议（不在此次重构中改动；待用户决定）：
```java
@SpringBootApplication
@ConfigurationPropertiesScan("dev.kaiwen.bikes.config.properties")
@EnableJpaRepositories(basePackages = "dev.kaiwen.bikes")
@EntityScan(basePackages = "dev.kaiwen.bikes")
@EnableAsync
public class DublinBikesApplication { ... }
```

---

## 4. 环境变量来源

直接复用 Flask 项目 `.env.example` 的所有键（见 `flask-app/.env.example`）；唯一不同：

- `DATABASE_URL` 从 `mysql+pymysql://...` 转换为 JDBC URL：
  - 原：`mysql+pymysql://root:pw@localhost:3306/dublinbikes`
  - 新：`jdbc:mysql://localhost:3306/dublinbikes?useUnicode=true&characterEncoding=utf-8&serverTimezone=UTC`
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
