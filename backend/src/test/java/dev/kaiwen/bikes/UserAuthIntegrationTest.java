package dev.kaiwen.bikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kaiwen.bikes.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class UserAuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @Test
    void registerActivateLoginMeRefreshLogoutFlow() throws Exception {
        String username = "user" + System.nanoTime();
        String email = username + "@example.com";
        String password = "password12";

        mockMvc.perform(
                        post("/api/users/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"username":"%s","email":"%s","password":"%s"}
                                        """
                                                .formatted(username, email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_active").value(false));

        var user = userRepository.findByUsername(username).orElseThrow();
        user.setEmailVerificationCode("123456");
        user.setEmailVerificationCodeExpiresAt(
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5));
        userRepository.save(user);

        mockMvc.perform(
                        post("/api/users/activate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","code":"123456"}
                                        """
                                                .formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_active").value(true));

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/users/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"identifier":"%s","password":"%s"}
                                                """
                                                        .formatted(username, password)))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.path("data").path("access_token").asText();
        String refreshToken = loginJson.path("data").path("refresh_token").asText();
        assertThat(accessToken).isNotBlank();

        mockMvc.perform(
                        get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        mockMvc.perform(
                        post("/api/users/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"refresh_token":"%s"}
                                        """
                                                .formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").isNotEmpty());

        mockMvc.perform(
                        post("/api/users/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void authorizationRulesIgnoreAcceptHeader() throws Exception {
        // Regression: Spring Security 6's default MvcRequestMatcher negotiates on Accept,
        // which made any request with `Accept: text/event-stream` (sent by fetchEventSource)
        // fall through every requestMatcher and end up at denyAll() — returning 401 even
        // with a valid JWT or on permitAll() routes.
        String username = "sseaccept" + System.nanoTime();
        String email = username + "@example.com";
        String password = "password12";

        mockMvc.perform(
                        post("/api/users/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"username":"%s","email":"%s","password":"%s"}
                                        """
                                                .formatted(username, email, password)))
                .andExpect(status().isOk());

        var user = userRepository.findByUsername(username).orElseThrow();
        user.setEmailVerificationCode("123456");
        user.setEmailVerificationCodeExpiresAt(
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5));
        userRepository.save(user);

        mockMvc.perform(
                        post("/api/users/activate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","code":"123456"}
                                        """
                                                .formatted(email)))
                .andExpect(status().isOk());

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/users/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"identifier":"%s","password":"%s"}
                                                """
                                                        .formatted(username, password)))
                        .andExpect(status().isOk())
                        .andReturn();
        String accessToken =
                objectMapper
                        .readTree(loginResult.getResponse().getContentAsString())
                        .path("data")
                        .path("access_token")
                        .asText();

        // Authenticated route with a non-matching Accept must reach Spring MVC content
        // negotiation (406), not be short-circuited to 401 by the security layer.
        mockMvc.perform(
                        get("/api/users/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isNotAcceptable());

        // permitAll route with a non-matching Accept must also reach MVC (406), not 401.
        mockMvc.perform(get("/api/stations/").header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isNotAcceptable());

        // Authenticated route with the matching Accept the controller produces.
        mockMvc.perform(
                        get("/api/users/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }
    @Test
    void loginWithUnverifiedEmailReturnsEmailNotVerified() throws Exception {
        String username = "pending" + System.nanoTime();
        String email = username + "@example.com";
        String password = "password12";

        mockMvc.perform(
                        post("/api/users/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"username":"%s","email":"%s","password":"%s"}
                                        """
                                                .formatted(username, email, password)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/users/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}
                                        """
                                                .formatted(username, password)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.msg").value("email not verified"));
    }

}
