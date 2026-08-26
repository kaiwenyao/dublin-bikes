package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 邮箱验证码 + 激活链接 REST API 集成测试：真实 PostgreSQL + Flyway。
 *
 * <p>覆盖 {@code /api/users/send-verification-code} 与 {@code /api/users/activate-by-token}：
 * <ul>
 *   <li>发送验证码后数据库落库 6 位数字码与激活 token</li>
 *   <li>冷却期内重复发送不更新验证码（防刷）</li>
 *   <li>通过激活 token 激活成功后可以登录</li>
 *   <li>无效激活 token 返回 401</li>
 * </ul>
 *
 * <p>邮件实际发送在 it profile 下被跳过（{@code spring.mail.host} 为空），
 * 这里验证的是验证码的生成、持久化与激活链路。
 */
class UserVerificationRestIntegrationTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAllInBatch();
    }

    @Test
    void sendCodeThenActivateByToken_activatesAccount() {
        String username = "verify" + System.nanoTime();
        String email = username + "@example.com";
        register(username, email);

        // 发送验证码 → 数据库落库验证码与激活 token
        ResponseEntity<Map> sendResp =
                restTemplate.postForEntity(
                        "/api/users/send-verification-code",
                        jsonBody(Map.of("identifier", email)),
                        Map.class);
        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendResp.getBody()).isNotNull();
        assertThat(sendResp.getBody().get("code")).isEqualTo(0);

        User user = userRepository.findByUsername(username).orElseThrow();
        assertThat(user.getEmailVerificationCode()).hasSize(6);
        assertThat(user.getActivationToken()).isNotBlank();
        assertThat(user.getEmailVerificationCodeExpiresAt()).isNotNull();

        // 通过激活 token 激活
        ResponseEntity<Map> activateResp =
                restTemplate.postForEntity(
                        "/api/users/activate-by-token",
                        jsonBody(Map.of("token", user.getActivationToken())),
                        Map.class);
        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> activateData = (Map<?, ?>) activateResp.getBody().get("data");
        assertThat(activateData.get("is_active")).isEqualTo(true);

        // 激活后可以登录
        ResponseEntity<Map> loginResp =
                restTemplate.postForEntity(
                        "/api/users/login",
                        jsonBody(Map.of("identifier", username, "password", "password12")),
                        Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) loginResp.getBody().get("data")).get("access_token"))
                .isNotNull();
    }

    @Test
    void resendWithinCooldown_keepsExistingCode() {
        String username = "cooldown" + System.nanoTime();
        String email = username + "@example.com";
        register(username, email);

        restTemplate.postForEntity(
                "/api/users/send-verification-code",
                jsonBody(Map.of("identifier", email)),
                Map.class);
        String firstCode =
                userRepository.findByUsername(username).orElseThrow().getEmailVerificationCode();
        assertThat(firstCode).isNotNull();

        // 冷却期内（60s）再次发送：接口仍返回成功，但验证码不变
        restTemplate.postForEntity(
                "/api/users/send-verification-code",
                jsonBody(Map.of("identifier", email)),
                Map.class);
        String secondCode =
                userRepository.findByUsername(username).orElseThrow().getEmailVerificationCode();
        assertThat(secondCode).isEqualTo(firstCode);
    }

    @Test
    void activateByBogusToken_returns401() {
        ResponseEntity<Map> resp =
                restTemplate.postForEntity(
                        "/api/users/activate-by-token",
                        jsonBody(Map.of("token", "no-such-token")),
                        Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        // AUTH_ERROR = 40101
        assertThat(resp.getBody().get("code")).isEqualTo(40101);
    }

    private void register(String username, String email) {
        ResponseEntity<Map> resp =
                restTemplate.postForEntity(
                        "/api/users/register",
                        jsonBody(
                                Map.of(
                                        "username", username,
                                        "email", email,
                                        "password", "password12")),
                        Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
