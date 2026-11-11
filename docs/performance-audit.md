# 性能审计：Dublin Bikes

> 审计范围：`backend`（Spring Boot）、`chat-service`（FastAPI + LangChain）、`prediction-service`、`scraper`、`frontend`（React/Vite）。
> 关注点是**运行时**阻塞：随数据量增长而退化、随并发上涨而耗尽资源、以及每条请求上的固定浪费。构建流水线（Jenkins）与部署拓扑只在"遗留项"里提及。

## 0. 结论摘要

| 级别 | 问题 | 位置 | 状态 |
|---|---|---|---|
| P0 | 每条聊天消息新建 SQLAlchemy Engine（绕过连接池，每条消息多次 TLS 握手，Engine 不释放） | `chat-service/main.py` | 已修复 |
| P0 | `/api/stations/status` 对整张 `availability` 表做窗口函数（约 99 万行） | `AvailabilityRepository`、`StationService` | 已修复 |
| P0 | `availability.requested_at` 无索引，保留策略每批顺序扫描 | `scraper/retention.py`、Flyway V5 | 已修复 |
| P0 | 事务内等待外部 HTTP（Google Maps ×5~6、chat-service 历史代理） | `JourneyService`、`ChatService` | 已修复 |
| P0 | 三个 `RestClient` 无连接池；SSE 每条消息新建 `HttpClient` | `*Config`、`ChatService` | 已修复 |
| P1 | 聊天 prompt 无历史上限，TTFT 与 token 成本随会话线性增长 | `chat-service/main.py` | 已修复 |
| P1 | 前端每个 SSE token 触发整条消息列表重渲染 + Markdown 全量重解析 | `Chat.tsx`、`ChatMessageContent.tsx` | 已修复 |
| P1 | 首页 LCP 图片是 1.2 MB PNG | `Home.tsx` | 已修复 |
| P2 | 每条消息重复查询 session；保留策略冗余索引；采集器逐站 `session.get` | `ChatService`、`V1`、`main_scraper.py` | 已修复 |
| — | JWT 过滤器每请求查一次 `users` | `JwtAuthenticationFilter` | 保留（即时登出的必要代价） |

数据量前提：采集器每 5 分钟写全量站点（约 115 站）→ **约 33,120 行/天**；保留 30 天 → **约 99 万行**。

---

## 1. chat-service：每条消息新建数据库 Engine（P0）

**现象.** 每条聊天消息都会与 PostgreSQL 建立新的 TCP/TLS 连接，并发上来后连接数持续上涨。

**证据.** `_memory()` 把 `connection=settings.chat_db_url`（字符串）交给 `SQLChatMessageHistory`。LangChain 的实现里，字符串走 `create_engine(url)`，且构造时还会执行一次 `metadata.create_all(engine)`（即一次表存在性查询）。因此每个 `/chat/stream` 请求都：

1. 新建一个 SQLAlchemy Engine（默认 QueuePool，pool_size 5 + overflow 10）；
2. 用这个新池对 Supabase 做一次全新握手；
3. 读历史 1 次、写用户消息 1 次、写 AI 消息 1 次 → 多次 checkout；
4. 请求结束后 Engine/池不会被 `dispose()`。

此前为工具查询和 session upsert 加的 `psycopg_pool` 完全被 LangChain 这条路径绕开了。

**修复.**

- 新增 `_sqlalchemy_engines` + 双检锁（与既有 `_db_pools` 同一写法），按 URL 复用 `create_engine(...)`，池大小固定为 2 + 3。
- 新增 `_repair_sslmode()`，被 `_psycopg_conninfo()`（去掉 `+psycopg`）与新的 `_sqlalchemy_url()`（保留驱动后缀）共用。
- `_memory()` 传入共享 Engine；新增 FastAPI `lifespan`：预热与 `yield` 在**同一个** `try/finally` 内，关闭时（正常退出、异常退出、取消，包括预热期间被取消）都会 `dispose()` 所有 Engine 并 `close()` 所有 psycopg 池——清理逻辑保持同步，避免取消场景下 `await` 再次抛 `CancelledError` 而跳过释放。
- `requirements.txt` 显式声明 `SQLAlchemy`（此前只是 `langchain-community` 的传递依赖）。

**验证.** `test_sqlalchemy_engine_is_lazy_and_cached_per_url`、`test_memory_reuses_shared_engine`；`test_lifespan_disposes_databases_on_normal_shutdown` 与 `..._when_the_context_raises` 分别在正常退出、异常注入两种路径断言清理被执行。运行期可用 `pg_stat_activity` 观察 `application_name='chat-service'` 的连接数不随消息数增长。

---

## 2. backend：`/api/stations/status` 的全表窗口函数（P0）

**现象.** 地图页每次加载都要对约 99 万行做 `ROW_NUMBER() OVER (PARTITION BY number ...)`。

**证据.** `findLatestPerStation()` 的 SQL 是 `FROM (SELECT a.*, ROW_NUMBER() OVER (...) FROM availability a) ranked WHERE rn = 1`，子查询没有时间过滤。`StationService.getLatestStatusForAllStations()` 是唯一的调用方，也就是地图首屏的 `/api/stations/status`。

**修复.**

- 删除无过滤的 `findLatestPerStation()`；窗口查询拆成两个，语义不同：
  - `findLatestPerStationSince(since)` 按 `timestamp`（JCDecaux `last_update`，数据生成时间）过滤，仍供 `JourneyService` 使用——行程规划不该推荐上游数据已经过期的站点。
  - `findLatestPerStationScrapedSince(since)` 按 `requested_at`（采集时间）过滤，供 `/status` 使用。两个字段的时间含义见 `scraper/main_scraper.py`：`timestamp = last_update`、`requested_at = utc_now()`。
- `StationService` 使用 `LATEST_WINDOW_HOURS = 24`（≈33k 行）作为窗口。
- 行为变化：只有"采集器 24h 没有为这个站点写入任何行"时，站点才会从 `/status` 消失（前端本来就有"无状态 → 灰色标记 / `--`"的兜底）。站点即使上游 `last_update` 冻结数天（停运检修、坏桩、上游接口卡住），只要仍在被采集就继续上报——这正是 `timestamp` 过滤会误伤、而 `requested_at` 过滤不会的场景。

**验证.** `AvailabilityRepositoryTest`：`findLatestPerStationSince` 的 3 个用例（含"同一 timestamp 写两次只返回一行"）+ 新增 `findLatestPerStationScrapedSince_keepsStationWhoseUpstreamTimestampIsStale`（旧 `timestamp` + 新 `requested_at` 必须保留）与 `_dropsStationWithoutRecentScrapes`；集成测试 `StationRestIntegrationTest#getStatus_keepsStationWhoseUpstreamTimestampIsStaleButScrapeIsRecent` 端到端覆盖该语义。`StationServiceTest` 用 `ArgumentCaptor` 断言窗口约为 24h 且走的是采集时间查询。线上可用 `EXPLAIN (ANALYZE, BUFFERS)` 确认扫描行数从 ~1M 降到 ~33k。

---

## 3. scraper：保留策略与写入路径（P0 / P2）

**现象.** 保留策略按 `requested_at < cutoff` 分批删除，但 `requested_at` 上没有索引。每小时一次的清理会顺序扫描；冷启动或长时间停机后积压时，N/5000 个批次各自从堆头重扫，成本接近平方级，而且这一步在采集主循环之前同步执行。

**修复.**

- 新增 Flyway `V5__availability_retention_index.sql`：先 `DROP INDEX CONCURRENTLY IF EXISTS`，再 `CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_availability_requested_at`（并同步了 IT 版本，按 V4 的既有约定去掉 `CONCURRENTLY`）。先删是必需的：中断的并发建索引会留下同名 **INVALID** 索引，而 `IF NOT EXISTS` 只看名字，重跑会「假成功」并让 retention/`/status` 继续扫表；V5 尚未发布，所以直接改写迁移是安全的（已部署环境需要一条检查 `pg_index.indisvalid` 的修复迁移）。这条索引同时服务 `/api/stations/status` 的采集时间窗口（见第 2 节）与保留策略，两处都是 `requested_at` 范围扫描。
- 集成测试 `AvailabilityIndexMigrationTest` 在真实 PostgreSQL 上把 V5 的建索引语句连续执行两次，断言最终恰好一个 `indisvalid` 索引（IT 的普通建索引版本覆盖不到并发路径，但能覆盖「遗留同名索引 → 重跑」这一行为）。
- 同一迁移删除 `ix_availability_number`：它是 V4 复合索引 `(number, "timestamp" DESC, id DESC)` 的最左前缀，`WHERE number = ?` 与 FK 检查都由复合索引承担，单列索引只增加每天 3.3 万次插入的写放大。
- `retention.py` 的批次查询加 `ORDER BY requested_at`，让计划稳定走新索引。
- `main_scraper.py` 改为一次 `SELECT` 预取全部站点，替代每轮 115 次 `session.get()`。

**验证.** `scraper/tests/test_retention.py`（5 个用例）通过；迁移在集成测试用的真实 PostgreSQL 上执行。

---

## 4. backend：事务跨越外部 I/O（P0）

**现象.** 并发几十个行程规划就能打满 20 个 Hikari 连接，其余接口排队。

**证据.** `JourneyService` 类上有 `@Transactional(readOnly = true)`，`plan()` 在其中串行调用两次 geocode、两次步行矩阵、骑行矩阵、终点步行矩阵（外加 resilience4j 重试的 500ms/1s 退避）。`ChatService.getSessionMessages` 同样在只读事务里等待 chat-service 返回历史。整个外部调用期间连接一直被占用。

**修复.**

- `JourneyService` 去掉类级事务：两次 repository 读取各自开启短只读事务，随后所有 Google 调用不再持有连接。安全性已核对：`Station` 字段均为 eager，`Availability.station` 是 LAZY 且全流程不访问。
- `ChatService.getSessionMessages` 去掉事务（`findById` 自带事务），HTTP 代理在事务之外。

**验证.** `JourneyServiceTest`（8 个用例）与 `ChatServiceTest` 全部通过；并发压测下连接占用时间应显著下降。

---

## 5. backend：HTTP 客户端不复用（P0）

**现象.** 每次外部调用都重新建连；SSE 每条消息新建一个 `HttpClient` 及其线程池。

**证据.** 三个 `RestClient` 都用 `SimpleClientHttpRequestFactory`（`HttpURLConnection`，无池、无 keep-alive）；`ChatService.chatStream` 里 `HttpClient.newBuilder()...build()` 位于请求路径上。

**修复.**

- `GoogleMapsConfig` / `ChatServiceConfig` / `PredictionServiceConfig` 各暴露一个 `HttpClient` bean（连接超时按上游区分，统一 HTTP/1.1），`RestClient` 改用 `JdkClientHttpRequestFactory` 并设置各自的读超时。
- 显式传入写 body 的 executor：`JdkClientHttpRequestFactory(HttpClient)` 在未指定 executor 时会回退到 `SimpleAsyncTaskExecutor`，即**每个 POST body 新建一个平台线程**且不限并发（比原来的 `SimpleClientHttpRequestFactory` 是回归）。新增 `HttpClientConfig#httpBodyExecutor`（2~8 线程、队列 64、CallerRuns 背压），三个 factory 共用；SSE 中继走 `HttpClient` 本身，不占这个小池。
- `ChatService` 通过构造器注入 `@Qualifier("chatServiceHttpClient")` 的共享客户端，SSE 复用同一连接与执行器；请求级 `HttpRequest.timeout(streamTimeoutMs)` 保持不变。
- 保留 HTTP/1.1：Uvicorn/FastAPI 在 HTTP/2 下会丢弃 `java.net.http.HttpClient` 的 POST body（422）。

**验证.** 相关 `*ConfigIntegrationTest` 加载上下文通过；`ChatServiceTest` 的 SSE 用例改为注入真实 `HttpClient` 后全部通过。

---

## 6. chat-service：prompt 历史无上限（P1）

**现象.** 每轮把全部历史塞进 prompt，对话越长 TTFT 与 token 成本越高。

**修复.**

- 新增 `CHAT_HISTORY_MAX_MESSAGES`（默认 20）。
- `_window_history()` 取末尾 N 条后**向左回退到最近的 `HumanMessage` 边界**：`AIMessage(tool_calls=...)` 与其 `ToolMessage` 必须成对，截断在轮次中间会让 API 拒绝孤立的 ToolMessage；找不到 HumanMessage 时保留全量（安全退化）。
- `_build_messages()` 应用窗口，`chat_stream` 显式传入配置值。

**验证.** `test_window_history_*` 三个用例 + `test_build_messages_applies_the_window`。

---

## 7. frontend：流式渲染的 O(n²)（P1）

**现象.** 长回答流式输出时掉帧，长会话更明显。

**证据.** `onMessage` 每个 chunk 都 `setMessages`（整数组替换），消息列表内联渲染 `ChatMessageContent`（`react-markdown`），因此每个 token 都会把**所有**历史消息的 Markdown 重解析一遍。

**修复.**

- 新增 `src/lib/stream-batcher.ts`：50ms 定时器合并 chunk（用定时器而非 `requestAnimationFrame`，因为 `openWhenHidden: true` 时后台标签页 rAF 不触发）。
- `Chat.tsx` 接入：`[DONE]`/`onDone`/abort 先 `flush()`，真错误 `cancel()`（避免把残余文本追加到错误提示后面），`finally` 兜底 flush。
- **停止生成的竞态**：缓冲期内点"停止"时，assistant 气泡在已渲染内容上仍是空的，旧的清理逻辑会把气泡连同首段内容一起删掉。现在 `Chat.tsx` 用 `activeBatcherRef` 保存当前 batcher，`handleStopGeneration` **先同步 `flush()` 再做空气泡清理**；两个 `setMessages` 都是函数式更新，React 按入队顺序执行，因此清理一定能看到刚刷入的文本。消息列表的追加/清理逻辑抽到 `src/lib/chat-messages.ts` 以便测试。
- `ChatMessageContent` 用 `memo` 包裹（props 只有一个字符串），历史消息不再重解析。

**验证.** `stream-batcher.test.mjs`（5 个用例）+ `chat-stream-stop.test.mjs`（4 个用例，覆盖"缓冲中停止"与"先删后刷会丢文本"两种顺序）+ 既有前端用例全部通过；`npm run build` 通过。

---

## 8. frontend：首页 LCP 图片（P1）

**现象.** 1024×686 PNG 共 1.2 MB，是首页最大且最先渲染的资源。

**修复.** 用 `cwebp -q 82` 生成 `cover.webp`（**123 KB**），`Home.tsx` 改为 `<picture>` + PNG 兜底，并补 `width/height`、`fetchPriority="high"`、`decoding="async"`。

**验证.** 构建产物中 `cover-*.webp` 为 125.73 KB。

---

## 9. 验证方式

```bash
# backend（mvn test 跑全部 187 个，含 21 个 Testcontainers 集成测试；
# CI 用 -Dtest='!dev.kaiwen.bikes.it.**' 只跑 166 个单测；checkstyle 在 validate 阶段）
cd backend && mvn -o test

# backend 集成测试（需要 Docker + Testcontainers；会执行 V5 迁移）
cd backend && mvn -o test -Dspring.profiles.active=it \
  -Dtest='dev.kaiwen.bikes.it.**' -Dsurefire.failIfNoSpecifiedTests=false

# chat-service
cd chat-service && ./.venv/bin/python -m pytest -q

# scraper（任意带 SQLAlchemy 的虚拟环境）
cd scraper && <python> -m pytest -q

# frontend
cd frontend && npm test && npm run lint && npm run build
```

本次执行结果：backend **187 passed**（`mvn test`，其中 **21 个**是 Testcontainers PostgreSQL 17 集成测试，日志可见 `Migrating schema to version 5`；按 CI 口径只跑单测为 **166**）、chat-service **35 passed / 1 skipped**、scraper **5 passed**、frontend **31 passed**；`mvn -o -DskipTests verify`（Checkstyle + SpotBugs）与 `npm run lint` 均通过。

补充的人工核对：

- 对 `/api/stations/status` 的 SQL 跑 `EXPLAIN (ANALYZE, BUFFERS)`，确认扫描行数降到约 3.3 万。
- 连发 10 条聊天消息，`SELECT application_name, count(*) FROM pg_stat_activity GROUP BY 1` 中 chat-service 连接数应稳定在池上限内。
- 地图、站点详情、预测、行程规划、带定位的聊天各回归一次。

> 顺带修复的测试缺陷：`SecurityAsyncDispatchIntegrationTest.protectedSseAllowsAsyncRedispatchAfterAuthentication`
> 原本在 `CompletableFuture.runAsync` 线程里调用 `emitter.send()`，而 Servlet 线程此时仍在写
> `MockHttpServletResponse` 的 header（该类不是线程安全的），高负载下会抛
> `ConcurrentModificationException`。已在**未改动的 HEAD** 上用同一台机器复现（负载 ~17 时约 1/4 失败，空载时 0/9），
> 与本轮性能改动无关。现在改为在返回前 send（事件进入 `earlySendAttempts`，由 `initialize()` 在 Servlet 线程统一写出），
> 断言与异步 redispatch + 安全复检的覆盖范围不变；修复后高负载下连续 3 次全量通过。

---

## 10. 遗留项（本次未做）

1. **Google Maps 调用并行化**：`plan()` 里两次 geocode、两次步行矩阵、骑行矩阵、终点步行矩阵串行执行（约 5~6 次 RTT）。改为两阶段并行可省约 200~300 ms，但需要给 `JourneyService` 增加执行器依赖并调整 `@InjectMocks` 测试。
2. **Google 地理编码/距离矩阵缓存**：同一地址的重复查询可以命中 TTL 缓存（需要引入缓存组件或手写 TTL 容器）。
3. **Supabase 连接池端口（6543 / pgBouncer）**：可降低连接建立成本，但需要把 psycopg 的 `prepare_threshold` 调整为 `None`，否则事务级池化下会因命名预处理语句失败。
4. **CI 流水线**：`backend/Jenkinsfile` 的 Compile → Static Analysis → Unit → IT → Coverage 逐段串行，静态分析与单测可以并行。
5. **uvicorn 多 worker / 模型冷启动**：`prediction-service` 启动时从 Hugging Face 拉取 `.pkl`，冷启动时间取决于外网；`/predict` 是 CPU 密集但请求很轻，暂不需要多进程。
6. **前端 chunk 体积**：`Maps` 376 KB（含 recharts）、`Chat` 202 KB（react-markdown），可进一步拆分图表/占位组件。
