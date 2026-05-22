# 02 — 数据模型（JPA 实体映射）

> 字段名、表名、类型、是否可空、索引、外键 **必须与 Flask 现有 schema 完全一致** —— 因为生产数据库由 Alembic 管理，Spring Boot 端 **只读 schema、不变更 schema**（首版 Flyway 只做 baseline，不写 DDL）。

> JPA 实体包：`dev.kaiwen.bikes.model`（勿与 `dto` 混用）。
>
> 来源：`flask-app/app/models/{station,availability,user,weather,session,chat_history}.py`

---

## 1. `station` — 站点元数据

| 列 | 类型 | 约束 | 来源字段 |
|---|---|---|---|
| `number` | `INT` | PK | `Station.number`（来自 JCDecaux API） |
| `contract_name` | `VARCHAR(50)` | NOT NULL | `contract_name` |
| `name` | `VARCHAR(100)` | NOT NULL | `name` |
| `address` | `VARCHAR(200)` | NOT NULL | `address` |
| `latitude` | `FLOAT` | NOT NULL | `latitude` |
| `longitude` | `FLOAT` | NOT NULL | `longitude` |
| `banking` | `BOOLEAN` | NOT NULL | 是否支持刷卡 |
| `bonus` | `BOOLEAN` | NOT NULL | 是否奖励站点 |
| `bike_stands` | `INT` | NOT NULL | 总车位 |

```java
@Entity
@Table(name = "station")
@Getter @Setter @NoArgsConstructor
public class Station {
    @Id
    private Integer number;                  // 注意：不要 @GeneratedValue
    @Column(name = "contract_name", nullable = false, length = 50)
    private String contractName;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 200)
    private String address;
    @Column(nullable = false) private Float latitude;
    @Column(nullable = false) private Float longitude;
    @Column(nullable = false) private Boolean banking;
    @Column(nullable = false) private Boolean bonus;
    @Column(name = "bike_stands", nullable = false)
    private Integer bikeStands;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Availability> availabilities = new ArrayList<>();
}
```

---

## 2. `availability` — 站点可用性历史

| 列 | 类型 | 约束 | 来源 |
|---|---|---|---|
| `id` | `INT` | PK, AUTO_INCREMENT | `Availability.id` |
| `number` | `INT` | NOT NULL, FK → `station.number` | `number` |
| `available_bikes` | `INT` | NOT NULL | |
| `available_bike_stands` | `INT` | NOT NULL | |
| `status` | `VARCHAR(20)` | NOT NULL | e.g. `"OPEN"` |
| `last_update` | `BIGINT` | NOT NULL | 原始毫秒时间戳 |
| `timestamp` | `DATETIME` | NOT NULL, default `now()` | 转换后的时间 |
| `requested_at` | `DATETIME` | NOT NULL, default `now()` | 抓取时间 |

```java
@Entity
@Table(name = "availability")
@Getter @Setter @NoArgsConstructor
public class Availability {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "number", nullable = false)
    private Integer number;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "number", insertable = false, updatable = false)
    private Station station;

    @Column(name = "available_bikes", nullable = false)
    private Integer availableBikes;
    @Column(name = "available_bike_stands", nullable = false)
    private Integer availableBikeStands;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "last_update", nullable = false)
    private Long lastUpdate;
    @Column(nullable = false)
    private LocalDateTime timestamp;
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;
}
```

---

## 3. `user` — 用户

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | `INT` | PK AUTO_INCREMENT |
| `username` | `VARCHAR(64)` | UNIQUE, INDEX |
| `email` | `VARCHAR(120)` | UNIQUE, INDEX |
| `password_hash` | `VARCHAR(256)` | NOT NULL |
| `avatar_url` | `VARCHAR(255)` | NULL |
| `is_active` | `BOOLEAN` | NOT NULL, default `true` |
| `email_verification_code` | `VARCHAR(6)` | NULL |
| `email_verification_code_expires_at` | `DATETIME` | NULL |
| `email_verification_code_sent_at` | `DATETIME` | NULL |
| `activation_token` | `VARCHAR(64)` | UNIQUE, NULL, INDEX |
| `token_version` | `INT` | NOT NULL, default 0 |
| `created_at` | `DATETIME` | NOT NULL, server default `now()` |
| `updated_at` | `DATETIME` | NULL, on update `now()` |

```java
@Entity
@Table(name = "user", uniqueConstraints = {
    @UniqueConstraint(columnNames = "username"),
    @UniqueConstraint(columnNames = "email"),
    @UniqueConstraint(columnNames = "activation_token")
})
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(nullable = false, length = 64) private String username;
    @Column(nullable = false, length = 120) private String email;
    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;
    @Column(name = "avatar_url", length = 255) private String avatarUrl;
    @Column(name = "is_active", nullable = false) private Boolean isActive = true;
    @Column(name = "email_verification_code", length = 6)
    private String emailVerificationCode;
    @Column(name = "email_verification_code_expires_at")
    private LocalDateTime emailVerificationCodeExpiresAt;
    @Column(name = "email_verification_code_sent_at")
    private LocalDateTime emailVerificationCodeSentAt;
    @Column(name = "activation_token", length = 64)
    private String activationToken;
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;
    @Column(name = "created_at", nullable = false,
            insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

> **重要**：`is_active` 字段在 SQLAlchemy 列定义中默认 `True`，但 `user_service.register_user`（Flask 实现）会在创建用户后立刻将 `is_active` 强制设为 `False`，等待邮箱验证码 / 链接激活后才置 `True`。Java 端 `UserService.register` 必须复刻 **"列默认 true、创建逻辑强制 false"** 这一对组合，否则会破坏激活流程。

---

## 4. `weather_forecast` — 天气预报缓存

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | `INT` | PK AUTO_INCREMENT |
| `forecast_time` | `DATETIME` | UNIQUE, INDEX, NOT NULL |
| `temperature` | `FLOAT` | NOT NULL |
| `weather_code` | `INT` | NOT NULL |
| `description` | `VARCHAR(100)` | NULL |
| `icon` | `VARCHAR(20)` | NULL |
| `feels_like` | `FLOAT` | NULL |
| `pressure` | `INT` | NULL |
| `humidity` | `INT` | NULL |
| `uvi` | `FLOAT` | NULL |
| `clouds` | `INT` | NULL |
| `visibility` | `INT` | NULL |
| `wind_speed` | `FLOAT` | NULL |
| `wind_deg` | `INT` | NULL |
| `pop` | `FLOAT` | NULL, default 0.0 |
| `fetched_at` | `DATETIME` | NULL, default `now()` |

```java
@Entity
@Table(name = "weather_forecast")
@Getter @Setter @NoArgsConstructor
public class WeatherForecast {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "forecast_time", nullable = false, unique = true)
    private LocalDateTime forecastTime;
    @Column(nullable = false) private Float temperature;
    @Column(name = "weather_code", nullable = false) private Integer weatherCode;
    @Column(length = 100) private String description;
    @Column(length = 20) private String icon;
    @Column(name = "feels_like") private Float feelsLike;
    private Integer pressure;
    private Integer humidity;
    private Float uvi;
    private Integer clouds;
    private Integer visibility;
    @Column(name = "wind_speed") private Float windSpeed;
    @Column(name = "wind_deg") private Integer windDeg;
    private Float pop;
    @Column(name = "fetched_at") private LocalDateTime fetchedAt;
}
```

---

## 5. `sessions` — LLM 会话元数据

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | `VARCHAR(64)` | PK（格式：`user_{id}_chat_{slug}` 或 `user_{id}_chat_h_<32hex>`） |
| `user_id` | `INT` | NOT NULL, INDEX, FK → `user.id` |
| `title` | `VARCHAR(100)` | NULL |
| `created_at` | `DATETIME` | NULL, default `utcnow` |
| `updated_at` | `DATETIME` | NULL, on update `utcnow` |

```java
@Entity
@Table(name = "sessions")
@Getter @Setter @NoArgsConstructor
public class ChatSession {
    @Id @Column(length = 64) private String id;
    @Column(name = "user_id", nullable = false) private Integer userId;
    @Column(length = 100) private String title;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    @PrePersist void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
```

---

## 6. `message_store` — LangChain 聊天历史表（Python 独占）

> **归属变更**：本表由 **独立 Python `chat-service`** 通过 LangChain `SQLChatMessageHistory` 读写，**Spring 端不映射为 JPA 实体**。Java 侧获取历史一律走 `chat-service` HTTP 接口（见 `04-modules.md` §5）。
>
> 此处仍记录 schema，因为：
> 1. Spring 与 Python 共享同一个 MySQL 实例和库；Flyway baseline 需识别此表。
> 2. 排障 / 数据修复时可能需要直接读 SQL。

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | `INT` | PK AUTO_INCREMENT |
| `session_id` | `TEXT` | （Python 端 `db.Text`；MySQL 物理列为 `TEXT`） |
| `message` | `JSON` | LangChain `BaseMessage` 序列化结构 |

**`message` JSON 结构**（LangChain 1.x，由 Python 端写入）：
```json
{
  "type": "human",
  "data": {
    "content": "用户输入",
    "additional_kwargs": {},
    "type": "human"
  }
}
```
角色枚举：`human` ↔ `user`、`ai` ↔ `assistant`、`system`、`tool`。

> Spring 端无需实现 `ChatMemoryStore`、无需写 `ChatMessage` JPA 实体；如调试需要可在 `db/migration` 之外提供只读 native query，不纳入主代码路径。

---

## 7. Flyway 基线

- 第一个迁移脚本 `V1__baseline.sql`：**留空**或仅放注释。
- 配置：
  ```yaml
  spring:
    flyway:
      enabled: true
      baseline-on-migrate: true
      baseline-version: 1
      locations: classpath:db/migration
  ```
- 后续 schema 变更才用 `V2__xxx.sql`。
- **不要**让 Hibernate 自动 DDL：`spring.jpa.hibernate.ddl-auto: validate`。
