package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 用户认证 REST API 集成测试：真实 PostgreSQL + Flyway + Spring Security 过滤器链。
 *
 * <p>覆盖 {@code /api/users} 的完整认证流程：
 * <ul>
 *   <li>注册 → 邮箱激活 → 登录 → 获取当前用户 → 刷新令牌 → 登出 → 令牌失效</li>
 *   <li>安全层规则：未认证访问受保护接口返回 401</li>
 *   <li>登录未验证邮箱返回 403（业务码 EMAIL_NOT_VERIFIED）</li>
 * </ul>
 *
 * <p>使用真实 HTTP 请求（非 MockMvc）穿透完整的 Spring Security 过滤器链、
 * JWT 解析、数据库持久化，验证生产链路的端到端正确性。
 */
class UserAuthRestIntegrationTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAllInBatch();
    }

    @Test
    void fullAuthFlow_registerActivateLoginMeRefreshLogout() throws Exception {
        String username = "ituser" + System.nanoTime();
        String email = username + "@example.com";
        String password = "password12";

        // 1. Register — returns is_active=false
        ResponseEntity<Map> regResp =
                restTemplate.postForEntity(
                        "/api/users/register",
                        jsonBody(Map.of("username", username, "email", email, "password", password)),
                        Map.class);
        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> regBody = regResp.getBody();
        assertThat(regBody).isNotNull();
        assertThat(regBody.get("code")).isEqualTo(0);
        Map<?, ?> regData = (Map<?, ?>) regBody.get("data");
        assertThat(regData.get("username")).isEqualTo(username);
        assertThat(regData.get("email")).isEqualTo(email);
        assertThat(regData.get("is_active")).isEqualTo(false);

        // 2. Set verification code directly in DB (simulate email delivery)
        User user = userRepository.findByUsername(username).orElseThrow();
        user.setEmailVerificationCode("654321");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        userRepository.saveAndFlush(user);

        // 3. Activate with code
        ResponseEntity<Map> actResp =
                restTemplate.postForEntity(
                        "/api/users/activate",
                        jsonBody(Map.of("identifier", email, "code", "654321")),
                        Map.class);
        assertThat(actResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> actData = (Map<?, ?>) actResp.getBody().get("data");
        assertThat(actData.get("is_active")).isEqualTo(true);

        // 4. Login
        ResponseEntity<Map> loginResp =
                restTemplate.postForEntity(
                        "/api/users/login",
                        jsonBody(Map.of("identifier", username, "password", password)),
                        Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> tokenData = (Map<?, ?>) loginResp.getBody().get("data");
        String accessToken = (String) tokenData.get("access_token");
        String refreshToken = (String) tokenData.get("refresh_token");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        // 5. GET /me with access token
        HttpHeaders authHeaders = jsonHeaders();
        authHeaders.set("Authorization", "Bearer " + accessToken);
        ResponseEntity<Map> meResp =
                restTemplate.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) meResp.getBody().get("data")).get("username")).isEqualTo(username);

        // 6. Refresh token
        ResponseEntity<Map> refreshResp =
                restTemplate.postForEntity(
                        "/api/users/refresh",
                        jsonBody(Map.of("refresh_token", refreshToken)),
                        Map.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newAccessToken =
                (String) ((Map<?, ?>) refreshResp.getBody().get("data")).get("access_token");
        assertThat(newAccessToken).isNotBlank();

        // 7. Logout
        ResponseEntity<Map> logoutResp =
                restTemplate.exchange(
                        "/api/users/logout", HttpMethod.POST, new HttpEntity<>(authHeaders), Map.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. Old token is now invalid (token_version incremented)
        ResponseEntity<Map> meAfterLogout =
                restTemplate.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(meAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithUnverifiedEmail_returns403EmailNotVerified() throws Exception {
        String username = "pending" + System.nanoTime();
        String email = username + "@example.com";
        String password = "password12";

        restTemplate.postForEntity(
                "/api/users/register",
                jsonBody(Map.of("username", username, "email", email, "password", password)),
                Map.class);

        ResponseEntity<Map> resp =
                restTemplate.postForEntity(
                        "/api/users/login",
                        jsonBody(Map.of("identifier", username, "password", password)),
                        Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // EMAIL_NOT_VERIFIED = 40301
        assertThat(body.get("code")).isEqualTo(40301);
        assertThat(body.get("msg")).isEqualTo("email not verified");
    }

    @Test
    void registerDuplicateUsername_returns409() throws Exception {
        String username = "dup" + System.nanoTime();
        String email = username + "@example.com";
        restTemplate.postForEntity(
                "/api/users/register",
                jsonBody(Map.of("username", username, "email", email, "password", "password12")),
                Map.class);

        ResponseEntity<Map> resp =
                restTemplate.postForEntity(
                        "/api/users/register",
                        jsonBody(
                                Map.of(
                                        "username", username,
                                        "email", "other" + username + "@example.com",
                                        "password", "password12")),
                        Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // USERNAME_EXISTS = 40901
        assertThat(body.get("code")).isEqualTo(40901);
    }

    @Test
    void protectedEndpointWithoutToken_returns401() throws Exception {
        ResponseEntity<Map> resp =
                restTemplate.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(jsonHeaders()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registerInvalidEmail_returns400ValidationError() throws Exception {
        ResponseEntity<Map> resp =
                restTemplate.postForEntity(
                        "/api/users/register",
                        jsonBody(
                                Map.of(
                                        "username", "bademail" + System.nanoTime(),
                                        "email", "not-an-email",
                                        "password", "password12")),
                        Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // VALIDATION_ERROR = 40001
        assertThat(body.get("code")).isEqualTo(40001);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        return new HttpEntity<>(body, jsonHeaders());
    }
}
