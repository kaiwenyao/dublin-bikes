package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.ChatSessionRepository;
import dev.kaiwen.bikes.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * 聊天会话 REST API 集成测试：真实 PostgreSQL + Flyway + Spring Security 过滤器链。
 *
 * <p>覆盖 {@code /api/chat} 的会话管理链路：
 * <ul>
 *   <li>POST /api/chat/stream：chat-service 不可达（it profile 指向假地址）时
 *       SSE 以错误结束，但会话行已同步落库（覆盖 ChatSession 的 @PrePersist）</li>
 *   <li>GET /api/chat/sessions：列出当前用户的会话</li>
 *   <li>GET /api/chat/sessions/{id}/messages：上游不可达时返回 500</li>
 *   <li>DELETE /api/chat/sessions/{id}：删除后会话列表为空</li>
 *   <li>未认证访问返回 401</li>
 * </ul>
 */
class ChatSessionRestIntegrationTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private ChatSessionRepository chatSessionRepository;

    @BeforeEach
    void cleanDatabase() {
        chatSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void streamFailureCreatesSession_thenListAndDelete() {
        Map<String, Object> account = registerActivateAndLogin();
        String accessToken = (String) account.get("accessToken");
        int userId = (int) account.get("userId");

        // 1. 发起聊天流：chat-service 在 it 环境不可达，上游连接失败 → 错误响应。
        //    但 ensureSession 在调用上游之前同步执行，会话行已落库。
        //    注意必须声明 Accept: text/event-stream，否则内容协商在进控制器前就失败。
        HttpHeaders streamHeaders = authHeaders(accessToken);
        streamHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> streamResp =
                restTemplate.postForEntity(
                        "/api/chat/stream",
                        new HttpEntity<>(Map.of("message", "hello"), streamHeaders),
                        String.class);
        assertThat(streamResp.getStatusCode().isError()).isTrue();

        // 2. 会话列表里能看到刚创建的会话
        String sessionId = "user_" + userId + "_chat_default";
        ResponseEntity<Map> sessionsResp =
                restTemplate.exchange(
                        "/api/chat/sessions",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(accessToken)),
                        Map.class);
        assertThat(sessionsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> sessions = (List<?>) sessionsResp.getBody().get("data");
        assertThat(sessions).hasSize(1);
        assertThat(((Map<?, ?>) sessions.get(0)).get("id")).isEqualTo(sessionId);

        // 3. 会话消息：上游 chat-service 不可达 → 500
        ResponseEntity<Map> messagesResp =
                restTemplate.exchange(
                        "/api/chat/sessions/" + sessionId + "/messages",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(accessToken)),
                        Map.class);
        assertThat(messagesResp.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // 4. 删除会话 → 列表清空
        ResponseEntity<Map> deleteResp =
                restTemplate.exchange(
                        "/api/chat/sessions/" + sessionId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(accessToken)),
                        Map.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> afterDelete =
                restTemplate.exchange(
                        "/api/chat/sessions",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(accessToken)),
                        Map.class);
        assertThat((List<?>) afterDelete.getBody().get("data")).isEmpty();
    }

    @Test
    void sessionsWithoutToken_returns401() {
        ResponseEntity<Map> resp =
                restTemplate.exchange(
                        "/api/chat/sessions",
                        HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 注册 → 直接落库验证码 → 激活 → 登录，返回 accessToken 与 userId。 */
    private Map<String, Object> registerActivateAndLogin() {
        String username = "chat" + System.nanoTime();
        String email = username + "@example.com";

        ResponseEntity<Map> regResp =
                restTemplate.postForEntity(
                        "/api/users/register",
                        jsonBody(
                                Map.of(
                                        "username", username,
                                        "email", email,
                                        "password", "password12"),
                                null),
                        Map.class);
        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        int userId = ((Number) ((Map<?, ?>) regResp.getBody().get("data")).get("id")).intValue();

        User user = userRepository.findByUsername(username).orElseThrow();
        user.setEmailVerificationCode("654321");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        userRepository.saveAndFlush(user);

        ResponseEntity<Map> actResp =
                restTemplate.postForEntity(
                        "/api/users/activate",
                        jsonBody(Map.of("identifier", email, "code", "654321"), null),
                        Map.class);
        assertThat(actResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> loginResp =
                restTemplate.postForEntity(
                        "/api/users/login",
                        jsonBody(Map.of("identifier", username, "password", "password12"), null),
                        Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken =
                (String) ((Map<?, ?>) loginResp.getBody().get("data")).get("access_token");

        return Map.of("accessToken", accessToken, "userId", userId);
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body, String accessToken) {
        return new HttpEntity<>(body, authHeaders(accessToken));
    }
}
