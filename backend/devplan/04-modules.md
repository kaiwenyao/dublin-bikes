# 04 — 业务模块详解

> 所有模块以 **保持 API 完全兼容** 为铁律。本文件按 模块 / 端点 / 请求 / 响应 / Service 关键逻辑 / 关键注意点 组织。来源已在每节标注 Flask 源文件路径。

---

## 1. Station 模块

源：`flask-app/app/api/station_routes.py`、`app/services/station_service.py`

### 1.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/stations/` | 全部站点元数据 |
| GET | `/api/stations/{number}/availability` | 该站点过去 1 天的可用性记录 |
| GET | `/api/stations/status` | 所有站点最新一条 availability |
| GET | `/api/stations/{number}/prediction` | 未来若干小时的可用车辆预测（委托 Prediction 模块） |

### 1.2 响应（保持原格式）

`/api/stations/`：
```json
{"code":0,"msg":"ok","data":[
  {"number":1,"contract_name":"dublin","name":"...","address":"...",
   "latitude":53.34,"longitude":-6.26,"banking":true,"bonus":false,
   "bike_stands":40}
]}
```
`/api/stations/{n}/availability`：
```json
{"code":0,"msg":"ok","data":[
  {"number":1,"available_bikes":12,"available_bike_stands":28,
   "status":"OPEN","last_update":1770047175000,
   "timestamp":"2025-01-20T10:00:00","requested_at":"2025-01-20T10:00:05"}
]}
```

`/api/stations/status`（所有站点最新一条 availability，**扁平数组**，不要再包一层 `{data:[...]}`）：
```json
{"code":0,"msg":"ok","data":[
  {"number":1,"available_bikes":12,"available_bike_stands":28,
   "status":"OPEN","last_update":1770047175000,
   "timestamp":"2025-01-20T10:00:00","requested_at":"2025-01-20T10:00:05"},
  {"number":2,"available_bikes":5,"available_bike_stands":35,
   "status":"OPEN","last_update":1770047175000,
   "timestamp":"2025-01-20T10:00:00","requested_at":"2025-01-20T10:00:05"}
]}
```
> 历史 Flask 实现在外层多嵌了一层 `data`，前端 `getStationsStatusAPI` 现在仍做"剥两层 data"的防御。Spring 端**必须**只返回单层 `ApiResponse.data = List<AvailabilityVO>`，避免回归。

### 1.3 关键查询（JPA）

- 列表：`stationRepository.findAllByOrderByNumberAsc()`
- 近一天历史：
  ```java
  @Query("""
      SELECT a FROM Availability a
      WHERE a.number = :number AND a.timestamp >= :since
      ORDER BY a.timestamp DESC""")
  List<Availability> findRecent(int number, LocalDateTime since);
  ```
  其中 `since = LocalDateTime.now().minusDays(1)`。
- 所有站点最新一条（**N+1 必须避免**）：用 group-by 子查询，参考 Flask `get_all_stations_latest_availability` 的实现（按 `MAX(timestamp) GROUP BY number` 后 join）。

```java
@Query(value = """
    SELECT a.* FROM availability a
    INNER JOIN (
        SELECT number, MAX(timestamp) AS max_ts
        FROM availability
        GROUP BY number
    ) latest
    ON a.number = latest.number AND a.timestamp = latest.max_ts
    """, nativeQuery = true)
List<Availability> findLatestPerStation();
```

### 1.4 异常映射

- station not found → `{"code":1,"msg":"station not found","data":null}` HTTP 404（保持原状）
- 注意 Flask 在 station 模块用的是 `code: 1`，不是 `40901` 等；不要"统一化"它。

---

## 2. Weather 模块

源：`flask-app/app/api/weather_routes.py`、`app/services/weather_service.py`

### 2.1 端点

| 方法 | 路径 |
|---|---|
| GET | `/api/weather` |

### 2.2 响应（兼容 OneCall API）

```json
{"code":0,"msg":"ok","data":{
  "current": {
    "dt": 1737378000, "temp": 4.5, "feels_like": 1.2,
    "pressure": 1019, "humidity": 78, "uvi": 0.3,
    "clouds": 90, "visibility": 10000,
    "wind_speed": 5.4, "wind_deg": 240,
    "weather": [{"id": 803, "description": "broken clouds", "icon": "04d"}]
  },
  "hourly": [
    {"dt": 1737381600, "temp": 4.7}
  ]
}}
```

### 2.3 Service 关键点

- 数据来源：本表 `weather_forecast`（已有外部进程写入）。
- 取数：`forecast_time >= 当前小时` 升序，limit 6（current + 5 hourly）。
- 若空，返回 `code=50001 msg="No weather data available in database"` HTTP 404。

```java
List<WeatherForecast> rows = weatherRepository
    .findTop6ByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(
        LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS));
if (rows.isEmpty()) throw new BusinessException(50001, "No weather data available in database", 404);
```

### 2.4 DTO

由 `WeatherMapper`（MapStruct）转换为上述 JSON 对应的 VO；用 `@JsonInclude(JsonInclude.Include.ALWAYS)` 保留 `null` 字段。

---

## 3. Journey 模块

源：`flask-app/app/api/journey_routes.py`、`app/services/journey_service.py`、`app/utils/calculateDistance.py`、`app/utils/api_retry.py`

### 3.1 端点

| 方法 | 路径 |
|---|---|
| POST | `/api/journey/plan` |

### 3.2 请求（两种互斥形态）

A. 地址：
```json
{"start_address":"O'Connell Street, Dublin","end_address":"UCD Belfield"}
```
B. 坐标：
```json
{"start":{"lat":53.34,"lon":-6.26},"end":{"lat":53.33,"lon":-6.25}}
```

### 3.3 响应

```json
{"code":0,"msg":"ok","data":{
  "route_info": {
    "start_station": {
      "number": 1,
      "name": "...",
      "address": "...",
      "coords": {"lat": 53.34, "lon": -6.26},
      "walking_time": 120,
      "available_bikes": 5
    },
    "end_station": {
      "number": 42,
      "name": "...",
      "address": "...",
      "coords": {"lat": 53.33, "lon": -6.25},
      "walking_time": 180,
      "available_bike_stands": 7
    },
    "cycling_route": {"cycling_time": 540},
    "total_duration": 840
  },
  "search_context": {
    "start_resolved": {"lat": 53.34, "lon": -6.26},
    "end_resolved": {"lat": 53.33, "lon": -6.25}
  }
}}
```
`route_info` / `search_context` 为前端 `JourneyPlanResponse` 的固定结构；不要返回扁平 `data.start_station`，否则地图页无法解构路线信息。
若无可行路线：`{"code":404,"msg":"no available route","data":null}` HTTP 404。

### 3.4 算法（端口 Flask 现有实现，**不要重新设计**）

1. 取最近 1 小时内每个站点的最新 availability（`MAX(timestamp) GROUP BY number`）；排除 `status != "OPEN"` 或时间戳早于 30 分钟前。
2. 按 Haversine 距离起点排序，取前 10 候选起点站（要求 `available_bikes > 0`），同理取 10 候选终点站（要求 `available_bike_stands > 0`）。
3. **批量调用** Google Distance Matrix（walking 模式），把起点到 10 个候选起点站的真实步行时间算出，按真实步行时间排序，取前 5；终点同理。
4. 对 5×5=25 个组合，**批量调用** Distance Matrix（bicycling 模式），构造 5×5 矩阵。
5. 全局最小化 `walk1 + cycle + walk2`，跳过 `start_station == end_station`。
6. 返回最优组合。

### 3.5 Google Maps 客户端

- 用 `RestClient`（Spring 6）直接打 REST API；不要引入 `google-maps-services-java` 以减少依赖。
- 重试装饰：复刻 `gmaps_retry`（指数退避，max_retries 默认 2）。可用 Resilience4j `@Retry` 注解：
  ```java
  @Retry(name = "gmaps", fallbackMethod = "gmapsFallback")
  public DistanceMatrixResult distanceMatrix(...) { ... }
  ```
- `Geocoding`：`/maps/api/geocode/json?address=...`
- `Distance Matrix`：`/maps/api/distancematrix/json?origins=lat,lon|lat,lon&destinations=...&mode=walking|bicycling`

### 3.6 距离工具

`DistanceUtils.haversineKm(lat1, lon1, lat2, lon2)`，端口 `calculate_distance` 直译；R=6371，注意对 `a` clamp 到 [0, 1] 防浮点越界。

---

## 4. User / Auth 模块

源：`flask-app/app/api/user_routes.py`、`app/services/user_service.py`、`app/contracts/{request,response}.py`、`app/utils/email.py`、`app/templates/email_verification.html`

### 4.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/users/register` | 注册 |
| POST | `/api/users/send-verification-code` | 发送/重发 6 位激活码 |
| POST | `/api/users/activate` | 用激活码激活 |
| POST | `/api/users/activate-by-token` | 用邮件 token 激活 |
| POST | `/api/users/login` | 登录 |
| POST | `/api/users/refresh` | 刷新 token |
| POST | `/api/users/logout` | 登出（递增 `token_version`） |
| GET | `/api/users/me` | 当前用户信息（Authorization: Bearer） |

### 4.2 关键请求 DTO（Bean Validation）

```java
public record UserRegistrationRequestDTO(
    @NotBlank @Size(min=3, max=64)
    @Pattern(regexp="^[A-Za-z0-9_.-]+$",
             message="username can only contain letters, numbers, '_', '-' and '.'.")
    String username,

    @NotBlank @Size(max=120)
    @Pattern(regexp="^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
             message="email format is invalid.")
    String email,

    @NotBlank @Size(min=8, max=128) String password,

    @Size(max=255) String avatarUrl
) {}

// POST /api/users/login —— identifier 双通道（username 或 email）
public record UserLoginRequestDTO(
    @NotBlank @Size(max=120) String identifier,
    @NotBlank @Size(min=8, max=128) String password
) {}

// POST /api/users/send-verification-code
public record SendVerificationCodeRequestDTO(
    @NotBlank @Size(max=120) String identifier
) {}

// POST /api/users/activate
public record ActivateAccountRequestDTO(
    @NotBlank @Size(max=120) String identifier,
    @NotBlank @Pattern(regexp="^\\d{6}$", message="code must be 6 digits.")
    String code
) {}

// POST /api/users/activate-by-token —— 邮件链接里的 64 字符 base64url token
public record ActivateByTokenRequestDTO(
    @NotBlank @Size(min=32, max=128) String token
) {}

// POST /api/users/refresh
public record RefreshTokenRequestDTO(
    @NotBlank String refreshToken    // 反序列化字段名 refresh_token（依赖 Jackson SNAKE_CASE）
) {}
```
> Controller 必须在反序列化前 normalize：
> - `username.trim()`（注册）
> - `email.strip().toLowerCase()`（注册）
> - `identifier.trim()`（登录 / 重发验证码 / 激活）：**不强制 lowercase**，由 Service 层决定按 username 精确匹配还是按 email 大小写不敏感匹配。
> - `code.trim()`、`token.trim()`：去除前后空白。
>
> 建议把 normalize 放在 Controller → Service 之间的辅助层，DTO 自身保持纯净。

**`identifier` 语义（重要）**：
- 命中 `@` 字符且匹配邮箱正则 → 按 `email` 字段查询（lowercase 比较）。
- 否则按 `username` 字段精确查询。
- 两端均查不到 → 401 `40101`（不要泄露"用户不存在/密码错误"差异）。

### 4.3 JWT 设计（**严格复刻 Flask 实现**）

- 两个独立密钥：`access` 与 `refresh`，HS256。
- payload：
  ```json
  {"sub":"<user_id>","ver":<token_version>,"type":"access|refresh","iat":...,"exp":...}
  ```
- `verifyAccessToken` 必须额外校验：
  1. `type == "access"`
  2. 从数据库 reload 用户、对比 `payload.ver == user.token_version`
  3. `user.is_active == true`
- `logout`：`user.token_version += 1` → 所有旧 token 立即失效。
- 配合 Spring Security：自定义 `JwtAuthenticationFilter extends OncePerRequestFilter`，把通过校验的用户写入 `SecurityContextHolder`。

**请求头读取顺序（兼容前端遗留实现）**：

前端 `request.ts` 与 `chat.ts` 在每次受保护请求上同时写入两个头：
```
Authorization: Bearer <access_token>
token: <access_token>
```

Spring `JwtAuthenticationFilter` 必须按以下顺序解析：
1. 先读 `Authorization` 头，若以 `Bearer ` 前缀开头则取后段。
2. 缺失或不合法时回退读 `token` 头（裸 token，不含 `Bearer ` 前缀）。
3. 两者都没有 → 跳过本过滤器（让 Security 链按公开/受保护路由策略判定 401）。

```java
private String resolveToken(HttpServletRequest req) {
    String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
    if (auth != null && auth.startsWith("Bearer ")) {
        return auth.substring(7).trim();
    }
    String legacy = req.getHeader("token");
    return (legacy != null && !legacy.isBlank()) ? legacy.trim() : null;
}
```

> `token` 头属于兼容补丁，**新代码不要写**；待前端清理后移除该回退分支。

### 4.4 登录 / 刷新响应合同

`POST /api/users/login` 成功返回：
```json
{"code":0,"msg":"ok","data":{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600,
  "token_type": "Bearer"
}}
```

`POST /api/users/refresh` 请求与响应：
```json
{"refresh_token":"..."}
```
```json
{"code":0,"msg":"ok","data":{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600,
  "token_type": "Bearer"
}}
```

前端 refresh 逻辑至少依赖 `access_token`，若刷新时沿用旧 refresh token，`refresh_token` 可返回旧值；推荐始终返回当前有效 refresh token。字段名必须保持 snake_case（依赖全局 Jackson `SNAKE_CASE` 或显式 `@JsonProperty`）。

### 4.5 邮件发送

- 模板：复制 `flask-app/templates/email_verification.html` 到 `src/main/resources/templates/email_verification.html`，把 `{code}` → `[[${code}]]`、`{expires_minutes}` → `[[${expiresMinutes}]]`、`{activation_link_section}` → Thymeleaf 条件块。
- 主题：`[Dublin Bikes] Verify your email to start riding`（与 Flask 完全一致）。
- 异步：`@Async("mailExecutor")`；`AsyncConfig` 暴露线程池（core=2, max=2, queue=100, name-prefix=`email-send-`）。
- 未配置 SMTP 时静默跳过（与 Flask 行为一致），但 INFO 级 log 记录"mail not configured, skip"。

### 4.6 注册流程要点

1. 解析、校验 DTO。
2. 检查 username / email 是否重复 → 抛 `BusinessException(40901/40902, ..., 409)`。
3. `passwordEncoder.encode(password)`（BCrypt，cost=10）。
4. 持久化 User，**强制 `isActive=false`**（覆盖列默认）；不生成验证码（验证码通过 `send-verification-code` 端点单独申请）。
5. 返回 `UserVO`：`{id, username, email, avatar_url, is_active=false, created_at}`。

**`created_at` 时区格式约定**：
- Flask `datetime.isoformat()` 输出**无时区** ISO-8601（如 `"2025-01-20T10:00:00"` 或 `"2025-01-20T10:00:00.123456"`）。
- Spring 端 `LocalDateTime` 默认序列化也是无时区 ISO-8601；**禁止**改成 `OffsetDateTime` 或追加 `Z`/`+00:00` 后缀，否则前端 `Date` 解析行为会从"本地时区"漂到"UTC"。
- 集成测试用 byte-for-byte 字符串对比断言（详见 §R9）。

### 4.7 验证码与激活

- 生成：6 位随机数字 + 64 字符随机 token（`secrets.token_urlsafe(48)` ≈ `Base64Url` 截断 64；Java 端用 `SecureRandom` + Base64URL）。
- 写库：`email_verification_code`、`email_verification_code_expires_at = now + 5min`、`email_verification_code_sent_at = now`、`activation_token`。
- 60 秒重发节流：`now < sent_at + 60s` → 401 `verification code can only be requested once per minute`（保留 Flask 文案）。
- 激活成功：清空三个 code 字段、`is_active=true`，**不要** 增加 `token_version`。

### 4.8 错误码映射

| 场景 | code | HTTP |
|---|---|---|
| DTO 校验失败 | 40001 | 400 |
| auth 失败（token / code 错误、过期、节流） | 40101 | 401 |
| username 已存在 | 40901 | 409 |
| email 已存在 | 40902 | 409 |
| user 其他冲突 | 40903 | 409 |

---

## 5. Chat 模块

源：`flask-app/app/api/chat_routes.py`、`app/services/chat_service.py`、`app/models/{chat_history,session}.py`

### 5.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/` | 普通同步响应 |
| POST | `/api/chat/stream` | SSE 流式响应 |
| GET | `/api/chat/sessions` | 当前用户会话列表 |
| GET | `/api/chat/sessions/{session_id}/messages` | 会话历史消息 |

全部需要 `Authorization: Bearer <access_token>`。

### 5.2 请求/响应

POST `/api/chat/`:
```json
// req
{"message":"...", "chat_id":"default"}
// resp
{"code":0,"msg":"ok","data":{"chat_id":"default","reply":"..."}}
```

POST `/api/chat/stream`: `Content-Type: text/event-stream`，每段
```
data: {"content":"..."}\n\n
```
结束：`data: [DONE]\n\n`。响应 header：
```
X-Accel-Buffering: no
Cache-Control: no-cache
Connection: keep-alive
```

### 5.3 会话 ID 生成（**复刻**）

```java
String prefix = "user_" + userId + "_chat_";
String candidate = prefix + chatId;
boolean validChatId = chatId.matches("[A-Za-z0-9_.-]+");
String sessionId = (candidate.length() <= 64 && validChatId)
    ? candidate
    : prefix + "h_" + sha256Hex(chatId).substring(0, 32);
```

### 5.4 LLM 调用（代理到 Python `chat-service`）

Spring 端 **不持有任何 LLM SDK**。`ChatService` 通过 `ChatServiceClient` 把同步 / 流式请求转发给 Python `chat-service`：

```java
@Service
@RequiredArgsConstructor
public class ChatServiceClient {
    private final RestClient restClient;        // 配置：base-url = app.chat-service.base-url
    private final WebClient  webClient;         // 或 JDK HttpClient，用于 SSE 转发

    public ChatReplyVO chat(String sessionId, int userId, String message) {
        return restClient.post().uri("/chat")
            .body(Map.of("session_id", sessionId, "user_id", userId, "message", message))
            .retrieve().body(ChatReplyVO.class);
    }

    public Flux<String> chatStream(String sessionId, int userId, String message) {
        return webClient.post().uri("/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(Map.of("session_id", sessionId, "user_id", userId, "message", message))
            .retrieve().bodyToFlux(String.class);   // 每条事件 = data: {"content":"..."} 的 payload
    }

    public List<ChatMessageVO> history(String sessionId) {
        return restClient.get().uri("/sessions/{id}/messages", sessionId)
            .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public String title(String firstMessage) {
        return restClient.post().uri("/chat/title")
            .body(Map.of("message", firstMessage))
            .retrieve().body(TitleResp.class).title();
    }
}
```

- **同步**：`POST /api/chat/` → Spring JWT 鉴权 → `_ensureSession` → `ChatServiceClient.chat()` → 包统一 `ApiResponse` 返回前端。
- **流式**：`POST /api/chat/stream` → Spring 用 `SseEmitter` 或 `ResponseBodyEmitter` **逐 token 转发** Python 端的 SSE 事件；前端收到的事件流格式与 Flask 完全一致（`data: {"content":"..."}\n\n` … `data: [DONE]\n\n`）。
- **历史 / message_store 读写**：由 Python `chat-service` 通过 LangChain `SQLChatMessageHistory` 独占完成。Spring 端不映射 `message_store` 实体。

### 5.5 会话表维护（仍在 Spring 端）

`sessions` 表归 Spring 拥有（持有 JWT 鉴权和用户 ACL，Python 端不直接访问）：

- `_ensureSession`：upsert `sessions(id, user_id)`，`updated_at = utcnow()`。
- 并发竞争：先 `findById`、不存在则 insert；捕获 `DataIntegrityViolationException` 后重读。
- **标题生成**（异步）：首条消息成功落库后，`@Async` 调用 `chatServiceClient.title(firstMessage)`；返回的标题取前 50 字符写入 `sessions.title`。异常吞掉、log warn。
- ACL：所有 `/api/chat/*` 端点必须先校验 `session.userId == 当前 JWT subject`，不一致返回 404 `{"code":404, "msg":"session not found"}`（防止信息泄露）。

### 5.6 历史接口

`GET /api/chat/sessions/{id}/messages`：

1. Spring 鉴权 → 查 `sessions` 表验证归属（404 兜底）。
2. 调 `chatServiceClient.history(sessionId)` 获取 `[{role, content}]`（角色映射在 Python 端完成：`human → user`、`ai → assistant`、`system/tool` 原样）。
3. 直接透传 → 包 `ApiResponse` 返回。

### 5.7 Python `chat-service` 规格

独立工程，建议放在仓库根级 `chat-service/`（与 `backend/`、`frontend/` 平级），或作为 monorepo 子目录 `services/chat-service/`。

**端点**：

| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| POST | `/chat`         | `{session_id, user_id, message}` | `{chat_id, reply}` |
| POST | `/chat/stream`  | `{session_id, user_id, message}` | `text/event-stream`，逐 token `data: {"content":"..."}\n\n`，结束 `data: [DONE]\n\n` |
| POST | `/chat/title`   | `{message}` | `{title}` |
| GET  | `/sessions/{session_id}/messages` | path | `[{role, content}]` |
| GET  | `/health`       | — | `{"status":"ok"}` |

**核心实现**（参考骨架）：

```python
# chat-service/main.py
from fastapi import FastAPI
from sse_starlette.sse import EventSourceResponse
from langchain_community.chat_message_histories import SQLChatMessageHistory
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage
import os, json

app = FastAPI()

DB_URL = os.environ["CHAT_DB_URL"]              # mysql+pymysql://...
QWEN_KEY = os.environ["ALIYUN_API_KEY"]
QWEN_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"

llm        = ChatOpenAI(model="qwen-plus", api_key=QWEN_KEY, base_url=QWEN_BASE)
llm_stream = ChatOpenAI(model="qwen-plus", api_key=QWEN_KEY, base_url=QWEN_BASE, streaming=True)

def memory(session_id: str) -> SQLChatMessageHistory:
    return SQLChatMessageHistory(session_id=session_id, connection_string=DB_URL)

@app.post("/chat")
def chat(req: dict):
    mem = memory(req["session_id"])
    mem.add_message(HumanMessage(content=req["message"]))
    ai = llm.invoke(mem.messages)
    mem.add_message(AIMessage(content=ai.content))
    return {"chat_id": req["session_id"], "reply": ai.content}

@app.post("/chat/stream")
async def chat_stream(req: dict):
    mem = memory(req["session_id"])
    mem.add_message(HumanMessage(content=req["message"]))
    history = mem.messages
    async def gen():
        chunks = []
        async for ev in llm_stream.astream(history):
            chunks.append(ev.content)
            yield {"data": json.dumps({"content": ev.content})}
        mem.add_message(AIMessage(content="".join(chunks)))
        yield {"data": "[DONE]"}
    return EventSourceResponse(gen())

@app.post("/chat/title")
def title(req: dict):
    prompt = ("Summarize the topic of this sentence in 6 words or less, "
              "output only the title without punctuation: " + req["message"][:200])
    out = llm.invoke([HumanMessage(content=prompt)])
    return {"title": out.content.strip()[:50]}

@app.get("/sessions/{sid}/messages")
def history(sid: str):
    role = {"human": "user", "ai": "assistant"}
    return [{"role": role.get(m.type, m.type), "content": m.content}
            for m in memory(sid).messages]
```

**契约约束**：

- `session_id` 始终由 Spring 生成（含 `user_id` 前缀），Python 端不做业务校验、不写 `sessions` 表。
- `message_store` 表 schema / 列名沿用 LangChain 默认（`id INT PK`、`session_id TEXT`、`message JSON`），Spring `Flyway` baseline 把它视为存量表。
- LangChain 版本锁定后写入 `chat-service/README.md`；升级前必跑兼容性回归（见 R1）。

**部署**：

- Dockerfile（`python:3.11-slim` + `uvicorn main:app --host 0.0.0.0 --port 8002`）。
- `docker-compose.yml` 增加服务 `chat-service`，与 `app`（Spring）、`mysql` 共网络。
- 端口：默认 `8002`（与 `prediction-service:8001` 错开）。

---

## 6. Prediction 模块

源：`flask-app/app/services/prediction_service.py`、`flask-app/machine_learning/{bike_availability_model.pkl, model_features.pkl}`

### 6.1 端点（沿用 station 模块路径）

| 方法 | 路径 |
|---|---|
| GET | `/api/stations/{number}/prediction` |

> **不接受 query 参数 `hours`**：后端始终返回从"当前小时起"的完整 hourly 预测序列（长度取决于 `weather_forecast` 表里可用的未来小时数，通常 24~48 小时）。前端 `getStationPredictionAPI(number, hours)` 自行在客户端 `slice(0, hours)`（目前 UI 提供 4h / 24h 两个视图）。**不要**接 `?hours=` 查询参数，否则与 Flask 行为分歧。

### 6.2 响应

```json
{"code":0,"msg":"ok","data":[
  {"forecast_time":"2025-01-20T11:00:00","predicted_available_bikes":18},
  {"forecast_time":"2025-01-20T12:00:00","predicted_available_bikes":15}
]}
```

### 6.3 模型加载方案选型

| 方案 | 优点 | 缺点 | 推荐场景 |
|---|---|---|---|
| **A. 独立 Python 微服务** | 零移植成本；模型保留 sklearn 原生 | 需要部署 Python 进程 | ✅ 首选 |
| B. ONNX 导出 + onnxruntime-java | 单 JVM 部署 | 需要重新 export 模型；版本契约 | 备选 |
| C. JPMML / pmml4s | 老 sklearn 兼容 | 不一定支持模型用到的所有 transformer | 仅当模型很简单时 |

**采用方案 A**：

```python
# prediction-service/main.py  (新仓库或子目录)
from fastapi import FastAPI
import pickle, pandas as pd

app = FastAPI()
model = pickle.load(open("bike_availability_model.pkl","rb"))
features = pickle.load(open("model_features.pkl","rb"))

@app.post("/predict")
def predict(payload: dict):
    df = pd.DataFrame(payload["rows"])[features]
    return {"predictions": [int(round(x)) for x in model.predict(df)]}
```

Spring 端：
```java
@Service
public class PredictionService {
    public List<PredictionPointVO> predict(int stationNumber) {
        Station station = stationRepo.findById(stationNumber)
            .orElseThrow(() -> new BusinessException(1, "Station "+stationNumber+" not found", 400));
        List<WeatherForecast> forecasts = weatherRepo
            .findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS));
        if (forecasts.isEmpty())
            throw new BusinessException(1, "No weather forecast data available to make predictions", 400);

        List<Map<String,Object>> rows = forecasts.stream()
            .map(f -> buildFeatureRow(station, f)).toList();

        var resp = restClient.post().uri("/predict").body(Map.of("rows", rows))
            .retrieve().body(PredictResp.class);

        return zipAndClamp(forecasts, resp.predictions(), station.getBikeStands());
    }
}
```

### 6.4 特征行（**字段名与训练保持一致**）

```java
Map.of(
  "station_id", station.getNumber(),
  "capacity",   station.getBikeStands(),
  "lat",        station.getLatitude(),
  "lon",        station.getLongitude(),
  "hour",       f.getForecastTime().getHour(),
  "day",        f.getForecastTime().getDayOfMonth(),
  "day_of_week",f.getForecastTime().getDayOfWeek().getValue() - 1,  // Mon=0..Sun=6
  "is_weekend", f.getForecastTime().getDayOfWeek().getValue() >= 6 ? 1 : 0,
  "avg_temperature", f.getTemperature(),
  "avg_humidity",    f.getHumidity(),
  "avg_pressure",    f.getPressure()
);
```

### 6.5 输出 clamp

`predicted_bikes = max(0, min(round(pred), station.bike_stands))` —— 与 Flask 一致。

### 6.6 启动预热

- Flask 在 `create_app` 时调用 `_load_model()` 预热（避免首请求慢）。
- Spring 端：Python 微服务自身在启动时 `pickle.load` 已完成；Spring 启动期可发一次健康探活：
  ```java
  @EventListener(ApplicationReadyEvent.class)
  void warmup() {
      try { restClient.get().uri("/health").retrieve().toBodilessEntity(); }
      catch (Exception e) { log.warn("prediction service warmup failed", e); }
  }
  ```

---

## 7. Cross-cutting

### 7.1 CORS

Flask 项目没有显式 CORS 配置（依赖部署反代或 Browser SOP），Spring 端建议显式：

```java
@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("http://localhost:*", "https://*.your-domain.com")
                        .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
```

### 7.2 安全过滤器链（最简）

```java
@Bean
SecurityFilterChain filter(HttpSecurity http, JwtAuthenticationFilter jwt) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers(
                "/api/stations/**", "/api/weather/**", "/api/journey/**",
                "/api/users/register", "/api/users/send-verification-code",
                "/api/users/activate", "/api/users/activate-by-token",
                "/api/users/login", "/api/users/refresh",
                "/actuator/health").permitAll()
            .requestMatchers("/api/users/me", "/api/users/logout",
                             "/api/chat/**").authenticated()
            .anyRequest().denyAll())
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```
