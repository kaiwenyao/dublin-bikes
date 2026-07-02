package dev.kaiwen.bikes.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.config.InternalAiToolProperties;
import dev.kaiwen.bikes.dto.response.InternalCurrentUserVO;
import dev.kaiwen.bikes.service.InternalAiToolService;
import dev.kaiwen.bikes.support.TestJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalAiToolControllerTest {

    @Mock private InternalAiToolService internalAiToolService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalAiToolController controller =
                new InternalAiToolController(internalAiToolService, new InternalAiToolProperties("test-token"));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(TestJson.snakeCaseConverter())
                .build();
    }

    @Test
    void currentUser_withValidServiceToken_returnsUser() throws Exception {
        when(internalAiToolService.currentUserForSession("user_1_chat_default"))
                .thenReturn(new InternalCurrentUserVO(1, "kai@example.com"));

        mockMvc.perform(get("/internal/ai/tools/current-user")
                        .param("session_id", "user_1_chat_default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("kai@example.com"));
    }

    @Test
    void currentUser_withoutServiceToken_returns401() throws Exception {
        mockMvc.perform(get("/internal/ai/tools/current-user")
                        .param("session_id", "user_1_chat_default"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(internalAiToolService);
    }

    @Test
    void currentUser_whenServiceTokenUnconfigured_returns503() throws Exception {
        InternalAiToolController controller =
                new InternalAiToolController(internalAiToolService, new InternalAiToolProperties(""));
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(TestJson.snakeCaseConverter())
                .build();

        unconfigured.perform(get("/internal/ai/tools/current-user")
                        .param("session_id", "user_1_chat_default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(internalAiToolService);
    }
}
