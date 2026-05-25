# Dublin Bikes 后端 Client 与 Google Maps 第三方调用说明

这篇文档解释本项目后端 `client` 包的作用，以及后端如何通过 `GoogleMapsClient` 调用 Google Maps 第三方 API。目标读者是不熟悉后端第三方 API 封装的人。

## 一句话版本

`client` 包是后端访问外部服务的适配层。本项目目前的 `client` 包只封装了 Google Maps 调用：

- 把用户输入的地址转换成经纬度。
- 计算用户、车站、目的地之间的步行或骑行时间。

业务层 `JourneyService` 不直接拼 Google URL，也不直接处理 Google 原始响应，而是调用 `GoogleMapsClient` 提供的方法。

## 为什么需要 client 包

如果业务代码直接调用第三方 API，`JourneyService` 里会混在一起：

- 路线规划业务逻辑。
- Google API URL。
- Google API key。
- HTTP 超时配置。
- Google 响应 JSON 解析。
- Google 错误码处理。
- 重试逻辑。

这会让业务代码变难读，也让测试变难写。

所以本项目把“访问 Google”这件事集中放在：

```text
backend/src/main/java/dev/kaiwen/bikes/client
```

这个包的定位是：

```text
Service 层需要外部数据
        ↓
调用 client 包
        ↓
client 包负责 HTTP 请求、响应解析、异常转换
        ↓
Service 层只拿到项目内部能理解的结果
```

## 相关文件

```text
backend/src/main/java/dev/kaiwen/bikes/client/GoogleMapsClient.java
backend/src/main/java/dev/kaiwen/bikes/client/LatLon.java
backend/src/main/java/dev/kaiwen/bikes/client/GoogleMapsTransientException.java
backend/src/main/java/dev/kaiwen/bikes/config/GoogleMapsConfig.java
backend/src/main/java/dev/kaiwen/bikes/config/GoogleMapsProperties.java
backend/src/main/java/dev/kaiwen/bikes/service/JourneyService.java
backend/src/main/java/dev/kaiwen/bikes/controller/JourneyController.java
```

职责划分：

| 文件 | 职责 |
| --- | --- |
| `JourneyController` | 接收前端路线规划请求 |
| `JourneyRequestNormalizer` | 校验请求是地址模式还是坐标模式 |
| `JourneyService` | 路线规划业务逻辑 |
| `GoogleMapsClient` | 调用 Google Geocoding API 和 Distance Matrix API |
| `LatLon` | 项目内部使用的经纬度值对象 |
| `GoogleMapsTransientException` | 表示 Google 或网络侧的临时失败 |
| `GoogleMapsConfig` | 创建访问 Google 的 `RestClient` |
| `GoogleMapsProperties` | 读取 `app.google-maps.*` 配置 |

## 整体调用链

用户在前端请求路线规划时，后端调用链是：

```text
Frontend
  -> POST /api/journey/plan
  -> JourneyController.plan()
  -> JourneyRequestNormalizer.normalize()
  -> JourneyService.plan()
  -> GoogleMapsClient.geocode()          如果输入是地址
  -> GoogleMapsClient.distanceMatrix()   计算步行/骑行时间
  -> JourneyService 选择最优车站组合
  -> JourneyPlanResponseVO
  -> Frontend
```

对应入口代码：

```java
@RestController
@RequestMapping("/api/journey")
@RequiredArgsConstructor
public class JourneyController {

    private final JourneyService journeyService;

    @PostMapping("/plan")
    public ApiResponse<JourneyPlanResponseVO> plan(@Valid @RequestBody JourneyRequestDTO request) {
        return ApiResponse.ok(journeyService.plan(JourneyRequestNormalizer.normalize(request)));
    }
}
```

逐行解释：

- `@RestController`：这个类处理 HTTP API 请求。
- `@RequestMapping("/api/journey")`：这个 controller 下的接口都以 `/api/journey` 开头。
- `private final JourneyService journeyService`：controller 不做复杂业务，只把请求交给 service。
- `@PostMapping("/plan")`：完整路径是 `POST /api/journey/plan`。
- `@RequestBody JourneyRequestDTO request`：从请求 JSON 中读取路线规划参数。
- `JourneyRequestNormalizer.normalize(request)`：先校验请求格式。
- `journeyService.plan(...)`：真正执行路线规划。
- `ApiResponse.ok(...)`：把结果包成统一响应结构。

## 请求格式：地址模式与坐标模式

`JourneyRequestDTO` 是：

```java
public record JourneyRequestDTO(
        String startAddress,
        String endAddress,
        GeoPointDTO start,
        GeoPointDTO end) {}
```

它支持两种输入方式。

地址模式：

```json
{
  "start_address": "Trinity College Dublin",
  "end_address": "Phoenix Park"
}
```

坐标模式：

```json
{
  "start": { "lat": 53.3438, "lon": -6.2546 },
  "end": { "lat": 53.3569, "lon": -6.3290 }
}
```

不能混用地址和坐标。`JourneyRequestNormalizer` 会拒绝这种请求：

```json
{
  "start_address": "Trinity College Dublin",
  "end": { "lat": 53.3569, "lon": -6.3290 }
}
```

原因是混用会让业务语义变模糊：起点和终点到底是都需要 geocode，还是只有一边需要 geocode。

## GoogleMapsClient 的定位

核心类：

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleMapsClient {

    public static final int UNREACHABLE_DURATION = Integer.MAX_VALUE;

    private final RestClient googleMapsRestClient;
    private final GoogleMapsProperties properties;
}
```

逐行解释：

- `@Component`：让 Spring 创建并管理这个对象，其他类可以通过构造函数注入它。
- `@RequiredArgsConstructor`：Lombok 自动生成构造函数，注入 `final` 字段。
- `@Slf4j`：提供 `log` 日志对象。
- `UNREACHABLE_DURATION`：表示某段路线不可达，用一个很大的整数占位。
- `RestClient googleMapsRestClient`：专门访问 `https://maps.googleapis.com` 的 HTTP 客户端。
- `GoogleMapsProperties properties`：读取 Google Maps API key 和超时配置。

`JourneyService` 注入它：

```java
private final GoogleMapsClient googleMapsClient;
```

这代表 `JourneyService` 只依赖一个项目内部抽象，而不是直接依赖 Google HTTP API。

## 地址转坐标：geocode

代码：

```java
@Retry(name = "gmaps", fallbackMethod = "geocodeFallback")
public LatLon geocode(String address) {
    requireApiKey();
    JsonNode root =
            googleMapsRestClient
                    .get()
                    .uri(
                            uriBuilder ->
                                    uriBuilder
                                            .path("/maps/api/geocode/json")
                                            .queryParam("address", address)
                                            .queryParam("key", properties.apiKey())
                                            .build())
                    .retrieve()
                    .body(JsonNode.class);
    return parseGeocodeResponse(root);
}
```

逐行解释：

- `@Retry(name = "gmaps", fallbackMethod = "geocodeFallback")`：这个方法失败时交给 Resilience4j 按 `gmaps` 配置重试。
- `requireApiKey()`：先确认后端 Google API key 存在。
- `googleMapsRestClient.get()`：发起 HTTP GET 请求。
- `.path("/maps/api/geocode/json")`：调用 Google Geocoding API。
- `.queryParam("address", address)`：把用户输入的地址传给 Google。
- `.queryParam("key", properties.apiKey())`：带上后端专用 Google API key。
- `.retrieve()`：执行请求并读取响应。
- `.body(JsonNode.class)`：把 Google 返回的 JSON 转成 Jackson `JsonNode`。
- `parseGeocodeResponse(root)`：把 Google 原始 JSON 转成项目内部的 `LatLon`。

成功时，Google 返回类似：

```json
{
  "status": "OK",
  "results": [
    {
      "geometry": {
        "location": {
          "lat": 53.3438,
          "lng": -6.2546
        }
      }
    }
  ]
}
```

项目内部只保留需要的部分：

```java
public record LatLon(double lat, double lon) {}
```

也就是：

```text
LatLon(53.3438, -6.2546)
```

## Geocoding 响应如何转成业务异常

解析代码的关键逻辑是：

```java
if ("OK".equals(status)) {
    JsonNode location = root.path("results").path(0).path("geometry").path("location");
    if (location.isMissingNode()) {
        throw addressNotResolved();
    }
    return new LatLon(location.path("lat").asDouble(), location.path("lng").asDouble());
}
if ("ZERO_RESULTS".equals(status)) {
    throw addressNotResolved();
}
if ("INVALID_REQUEST".equals(status)) {
    throw new BusinessException(ApiCodes.VALIDATION_ERROR, "invalid address", 400);
}
throw new GoogleMapsTransientException("geocode status: " + status);
```

含义：

- `OK`：地址解析成功，返回经纬度。
- `ZERO_RESULTS`：Google 找不到这个地址，转换成业务错误 `ADDRESS_NOT_RESOLVED`，HTTP 404。
- `INVALID_REQUEST`：请求参数不合法，转换成业务错误 `VALIDATION_ERROR`，HTTP 400。
- 其他状态：认为是 Google 侧或网络侧临时问题，抛 `GoogleMapsTransientException`，后续会走重试和 fallback。

这样做的好处是：controller 和前端不需要理解 Google 的全部状态码，只需要处理项目自己的错误码。

## 计算时间矩阵：distanceMatrix

代码：

```java
@Retry(name = "gmaps", fallbackMethod = "distanceMatrixFallback")
public int[][] distanceMatrix(List<LatLon> origins, List<LatLon> destinations, String mode) {
    requireApiKey();
    if (origins.isEmpty() || destinations.isEmpty()) {
        return new int[0][0];
    }
    JsonNode root =
            googleMapsRestClient
                    .get()
                    .uri(
                            uriBuilder ->
                                    uriBuilder
                                            .path("/maps/api/distancematrix/json")
                                            .queryParam("origins", joinCoords(origins))
                                            .queryParam("destinations", joinCoords(destinations))
                                            .queryParam("mode", mode)
                                            .queryParam("key", properties.apiKey())
                                            .build())
                    .retrieve()
                    .body(JsonNode.class);
    ...
}
```

逐行解释：

- `@Retry(...)`：Distance Matrix 请求失败时也会按 `gmaps` 策略重试。
- `origins`：起点列表。
- `destinations`：终点列表。
- `mode`：交通方式，例如 `walking` 或 `bicycling`。
- `joinCoords(origins)`：把多个经纬度拼成 Google 需要的格式。
- `/maps/api/distancematrix/json`：调用 Google Distance Matrix API。

`LatLon.toGoogleParam()` 负责把内部坐标转成 Google 参数：

```java
public record LatLon(double lat, double lon) {

    public String toGoogleParam() {
        return lat + "," + lon;
    }
}
```

例如：

```text
new LatLon(53.3438, -6.2546).toGoogleParam()
```

结果是：

```text
53.3438,-6.2546
```

多个点会用 `|` 连接：

```text
53.3438,-6.2546|53.3569,-6.329
```

## JourneyService 如何使用时间矩阵

`JourneyService` 不是直接问 Google “最优路线是什么”。它的策略是：

1. 先从数据库找出最近有可用车、可还车位的车站。
2. 用 Haversine 直线距离粗筛出候选车站。
3. 调 Google Distance Matrix 计算真实步行/骑行时间。
4. 根据总时间选出最优组合。

关键调用：

```java
RankedCandidates topStarts = rankByWalking(startResolved, startCandidates);
RankedCandidates topEnds = rankByWalking(endResolved, endCandidates);
```

这两步会计算：

```text
用户起点 -> 候选起点车站：walking
用户终点 -> 候选终点车站：walking
```

然后：

```java
int[][] cycleMatrix =
        googleMapsClient.distanceMatrix(startCoords, endCoords, "bicycling");

int[][] walkFromEndStations =
        googleMapsClient.distanceMatrix(endCoords, List.of(endResolved), "walking");
```

这两步会计算：

```text
起点车站 -> 终点车站：bicycling
终点车站 -> 用户目的地：walking
```

最后 `findBestRoute(...)` 会遍历候选组合，选出总时间最短的路线。

## RestClient 是在哪里创建的

`GoogleMapsClient` 没有自己 new HTTP 客户端，而是注入了一个配置好的 `RestClient`。

配置在：

```java
@Configuration
@EnableConfigurationProperties(GoogleMapsProperties.class)
public class GoogleMapsConfig {

    @Bean
    RestClient googleMapsRestClient(GoogleMapsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl("https://maps.googleapis.com")
                .requestFactory(factory)
                .build();
    }
}
```

逐行解释：

- `@Configuration`：这是 Spring 配置类。
- `@EnableConfigurationProperties(GoogleMapsProperties.class)`：启用 `app.google-maps.*` 配置绑定。
- `@Bean`：把返回的 `RestClient` 注册到 Spring 容器。
- `SimpleClientHttpRequestFactory`：设置底层 HTTP 请求配置。
- `setConnectTimeout`：连接 Google 服务器的超时时间。
- `setReadTimeout`：等待 Google 响应内容的超时时间。
- `.baseUrl("https://maps.googleapis.com")`：之后 `GoogleMapsClient` 只需要写路径，不需要每次写完整域名。

## 配置如何进入代码

配置类：

```java
@ConfigurationProperties(prefix = "app.google-maps")
public record GoogleMapsProperties(
        String apiKey, int connectTimeoutMs, int readTimeoutMs, int maxRetries) {}
```

它会读取：

```yaml
app:
  google-maps:
    api-key: ${GOOGLE_MAPS_API_KEY:}
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
    max-retries: 2
```

字段对应关系：

| YAML | Java |
| --- | --- |
| `app.google-maps.api-key` | `properties.apiKey()` |
| `app.google-maps.connect-timeout-ms` | `properties.connectTimeoutMs()` |
| `app.google-maps.read-timeout-ms` | `properties.readTimeoutMs()` |
| `app.google-maps.max-retries` | `properties.maxRetries()` |

注意：当前实际重试次数由 `resilience4j.retry.instances.gmaps.max-attempts` 控制，`app.google-maps.max-retries` 目前只是配置字段，没有被 `@Retry` 直接读取。

## 重试是如何工作的

项目使用 Resilience4j：

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
  <version>2.2.0</version>
</dependency>
```

配置在 `application.yaml`：

```yaml
resilience4j:
  retry:
    instances:
      gmaps:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        ignore-exceptions:
          - dev.kaiwen.bikes.exception.BusinessException
```

含义：

- `gmaps`：重试策略名称，对应 `@Retry(name = "gmaps")`。
- `max-attempts: 3`：最多尝试 3 次，包含第一次调用。
- `wait-duration: 500ms`：第一次失败后等待 500ms。
- `enable-exponential-backoff: true`：启用指数退避。
- `exponential-backoff-multiplier: 2`：等待时间按 2 倍增长。
- `ignore-exceptions: BusinessException`：业务错误不重试。

为什么业务错误不重试？

如果用户输入了一个不存在的地址，Google 返回 `ZERO_RESULTS`，重试三次也不会变成功。这个错误应该直接返回给前端，而不是浪费 API 调用额度。

## fallback 是什么

`geocode` 的 fallback：

```java
private LatLon geocodeFallback(String address, Throwable ex) {
    if (ex instanceof BusinessException businessException) {
        throw businessException;
    }
    log.warn("Google geocode failed for address={}", address, ex);
    throw mapsUnavailable();
}
```

含义：

- 如果是 `BusinessException`，原样抛出。
- 如果是网络错误、Google 临时错误、超时等，记录日志。
- 最后统一转成：

```java
new BusinessException(
        ApiCodes.GENERIC_ERROR,
        "Service temporarily unavailable, please try again later",
        500);
```

这样前端看到的是项目统一错误，而不是底层 Google 异常。

## API key：前端和后端必须分开

本项目同时有前端 Google Maps 和后端 Google Maps。

这两种 key 不能共用。

### 前端 key

前端 key 用在浏览器里，例如地图页面加载 Google Maps JavaScript API。浏览器端 key 一定会被用户看到，所以它不是秘密。

推荐限制：

```text
Application restriction:
HTTP referrers

Allowed referrers:
https://bikes.kaiwen.dev/*

API restrictions:
Maps JavaScript API
Places API
```

### 后端 key

后端 key 用在 `GoogleMapsClient`，请求由服务器发出，不会出现在浏览器 F12 里。

推荐限制：

```text
Application restriction:
IP addresses

Allowed IPs:
后端服务器或 k8s 出口网关的公网 IP

API restrictions:
Geocoding API
Distance Matrix API
```

如果以后后端改用 Routes API 或其他 Google Web Service，再把对应 API 加到后端 key 的 API restrictions 里。

## 后端 key 应该放在哪里

本地开发可以在 gitignored 的 `application-dev.yaml` 或环境变量中配置：

```yaml
app:
  google-maps:
    api-key: ${GOOGLE_MAPS_API_KEY:}
```

启动前设置：

```bash
export GOOGLE_MAPS_API_KEY=your_backend_google_maps_key
```

生产环境建议通过容器环境变量或挂载的生产配置注入，不要把真实 key 提交到 git。

## 如何确认后端用的是正确 key

后端如果没有配置 key，`GoogleMapsClient` 会在调用前检查：

```java
private void requireApiKey() {
    if (properties.apiKey() == null || properties.apiKey().isBlank()) {
        log.error("CONFIG_MISSING: app.google-maps.api-key is not set");
        throw mapsUnavailable();
    }
}
```

如果你看到日志：

```text
CONFIG_MISSING: app.google-maps.api-key is not set
```

说明后端没有拿到 `GOOGLE_MAPS_API_KEY`。

如果 key 存在但 Google 拒绝请求，常见原因是：

- 后端 key 误用了 HTTP referrer 限制。
- Google Cloud Console 里没有允许后端出口公网 IP。
- API restrictions 没有打开 Geocoding API 或 Distance Matrix API。
- Billing 没开或额度受限。
- 容器里的环境变量没有生效。

## 本地排查建议

检查当前 shell 是否配置了环境变量，不要输出完整 key：

```bash
test -n "$GOOGLE_MAPS_API_KEY" && echo "GOOGLE_MAPS_API_KEY is set" || echo "GOOGLE_MAPS_API_KEY is missing"
```

在服务器或容器内确认出口公网 IP：

```bash
curl https://api.ipify.org
```

这个 IP 应该被加入后端 Google API key 的 IP allowlist。

查看后端日志：

```bash
docker logs <backend-container> | grep -Ei "google|gmaps|maps|CONFIG_MISSING|distance|geocode"
```

不要把真实 API key 打到日志里，也不要把真实 key 贴到 issue、PR、聊天记录或前端配置中。

## 测试覆盖

Google 响应解析有对应测试：

```text
backend/src/test/java/dev/kaiwen/bikes/client/GoogleMapsClientTest.java
```

它覆盖了：

- `OK` 响应能转成 `LatLon`。
- `ZERO_RESULTS` 会变成地址无法解析错误。
- `INVALID_REQUEST` 会变成参数校验错误。
- 未知 Google 状态会被视为临时异常。

这样可以保证 Google 原始响应到项目内部异常的转换是稳定的。

## 设计原则总结

这个实现的核心思想是：

```text
Controller 只处理 HTTP 请求入口
Service 只处理业务决策
Client 只处理第三方 API 调用
Config 只处理外部配置和 Bean 创建
DTO / record 只表达数据结构
```

对 Google Maps 来说，具体表现为：

```text
JourneyController
  -> JourneyService
  -> GoogleMapsClient
  -> RestClient
  -> Google Maps API
```

这样的好处是：

- 业务逻辑不会被 Google API 细节污染。
- Google API key 只在后端配置和 client 层使用。
- 第三方错误可以统一转换成项目内部错误。
- 后续如果换 API 或升级接口，主要改 `client` 包，不需要大面积改 service。
