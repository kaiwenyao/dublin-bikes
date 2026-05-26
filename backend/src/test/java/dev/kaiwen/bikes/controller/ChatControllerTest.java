package dev.kaiwen.bikes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.ChatMessageVO;
import dev.kaiwen.bikes.dto.response.ChatReplyVO;
import dev.kaiwen.bikes.dto.response.ChatSessionVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import dev.kaiwen.bikes.service.ChatService;
import dev.kaiwen.bikes.support.TestJson;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ChatController chatController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(chatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(TestJson.snakeCaseConverter())
                .build();
    }

    @Test
    void chat_returnsOkEnvelope() throws Exception {
        when(chatService.chat("hello", "default"))
                .thenReturn(new ChatReplyVO("user_1_chat_default", "hi there"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"chat_id\":\"default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data.chat_id").value("user_1_chat_default"))
                .andExpect(jsonPath("$.data.reply").value("hi there"));
    }

    @Test
    void chatStream_returnsSseStream() throws Exception {
        doAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(2);
            emitter.send(SseEmitter.event().data("{\"content\":\"hello\"}"));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
            return null;
        }).when(chatService).chatStream(eq("hello"), eq("default"), any(SseEmitter.class));

        MvcResult mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"chat_id\":\"default\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("data:{\"content\":\"hello\"}");
                    assertThat(body).contains("data:[DONE]");
                });
    }

    @Test
    void listSessions_returnsOkEnvelope() throws Exception {
        List<ChatSessionVO> sessions = List.of(
                new ChatSessionVO("user_1_chat_a", "Title A", "2025-01-20T10:00:00", "2025-01-20T10:00:00"),
                new ChatSessionVO("user_1_chat_b", null, "2025-01-20T09:00:00", "2025-01-20T09:00:00"));
        when(chatService.listSessions()).thenReturn(sessions);

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value("user_1_chat_a"))
                .andExpect(jsonPath("$.data[0].title").value("Title A"))
                .andExpect(jsonPath("$.data[1].title").isEmpty());
    }

    @Test
    void getSessionMessages_returnsOkEnvelope() throws Exception {
        List<ChatMessageVO> messages = List.of(
                new ChatMessageVO("user", "hello"),
                new ChatMessageVO("assistant", "hi there"));
        when(chatService.getSessionMessages("user_1_chat_default")).thenReturn(messages);

        mockMvc.perform(get("/api/chat/sessions/user_1_chat_default/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("hello"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"));
    }

    @Test
    void getSessionMessages_whenNotFound_returns404() throws Exception {
        when(chatService.getSessionMessages("unknown"))
                .thenThrow(new BusinessException(ApiCodes.GENERIC_ERROR, "session not found", 404));

        mockMvc.perform(get("/api/chat/sessions/unknown/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCodes.GENERIC_ERROR))
                .andExpect(jsonPath("$.msg").value("session not found"));
    }

    @Test
    void deleteSession_returnsOkEnvelope() throws Exception {
        doNothing().when(chatService).deleteSession("user_1_chat_default");

        mockMvc.perform(delete("/api/chat/sessions/user_1_chat_default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.msg").value("ok"));
    }

    @Test
    void deleteSession_whenNotFound_returns404() throws Exception {
        doThrow(new BusinessException(ApiCodes.GENERIC_ERROR, "session not found", 404))
                .when(chatService)
                .deleteSession("unknown");

        mockMvc.perform(delete("/api/chat/sessions/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCodes.GENERIC_ERROR))
                .andExpect(jsonPath("$.msg").value("session not found"));
    }
}
