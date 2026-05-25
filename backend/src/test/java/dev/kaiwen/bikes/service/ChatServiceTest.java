package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kaiwen.bikes.client.ChatServiceClient;
import dev.kaiwen.bikes.config.ChatServiceProperties;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.ChatMessageVO;
import dev.kaiwen.bikes.dto.response.ChatReplyVO;
import dev.kaiwen.bikes.dto.response.ChatSessionVO;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.model.ChatSession;
import dev.kaiwen.bikes.repository.ChatSessionRepository;
import dev.kaiwen.bikes.security.AuthenticatedUser;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private ChatServiceClient chatServiceClient;
    @Mock private ChatTitleGenerator chatTitleGenerator;

    @InjectMocks private ChatService chatService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatServiceProperties properties =
            new ChatServiceProperties("http://localhost:8002", 1000, 5000, 10000, 3000);

    @BeforeEach
    void injectDependencies() throws Exception {
        setField("objectMapper", objectMapper);
        setField("properties", properties);
    }

    @BeforeEach
    void setUpSecurity() {
        AuthenticatedUser principal = new AuthenticatedUser(1);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generateSessionId_validShortChatId_returnsCandidate() {
        String result = chatService.generateSessionId(42, "abc123");
        assertThat(result).isEqualTo("user_42_chat_abc123");
    }

    @Test
    void generateSessionId_longChatId_returnsHashed() {
        String longChatId = "a".repeat(100);
        String result = chatService.generateSessionId(42, longChatId);
        assertThat(result).startsWith("user_42_chat_h_");
        assertThat(result).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void generateSessionId_invalidCharChatId_returnsHashed() {
        String result = chatService.generateSessionId(42, "abc@def");
        assertThat(result).startsWith("user_42_chat_h_");
    }

    @Test
    void generateSessionId_nullChatId_usesDefault() {
        String result = chatService.generateSessionId(42, null);
        assertThat(result).isEqualTo("user_42_chat_default");
    }

    @Test
    void ensureSession_existingSession_returnsIt() {
        ChatSession existing = session("user_1_chat_default", 1);
        when(chatSessionRepository.findById("user_1_chat_default")).thenReturn(Optional.of(existing));

        ChatSession result = chatService.ensureSession("user_1_chat_default", 1);

        assertThat(result).isEqualTo(existing);
    }

    @Test
    void ensureSession_newSession_createsAndReturns() {
        when(chatSessionRepository.findById("user_1_chat_new")).thenReturn(Optional.empty());
        ChatSession saved = session("user_1_chat_new", 1);
        when(chatSessionRepository.saveAndFlush(any(ChatSession.class))).thenReturn(saved);

        ChatSession result = chatService.ensureSession("user_1_chat_new", 1);

        assertThat(result.getId()).isEqualTo("user_1_chat_new");
        assertThat(result.getUserId()).isEqualTo(1);
    }

    @Test
    void chat_ensuresSessionAndCallsClient() {
        ChatSession session = session("user_1_chat_default", 1);
        when(chatSessionRepository.findById("user_1_chat_default"))
                .thenReturn(Optional.of(session));
        when(chatServiceClient.chat("user_1_chat_default", 1, "hello"))
                .thenReturn(new ChatReplyVO("user_1_chat_default", "hi there"));

        ChatReplyVO result = chatService.chat("hello", "default");

        assertThat(result.reply()).isEqualTo("hi there");
    }

    @Test
    void chat_firstMessageTriggersTitleGeneration() {
        ChatSession session = session("user_1_chat_default", 1);
        session.setTitle(null);
        when(chatSessionRepository.findById("user_1_chat_default"))
                .thenReturn(Optional.of(session));
        when(chatServiceClient.chat(any(), any(int.class), any()))
                .thenReturn(new ChatReplyVO("user_1_chat_default", "reply"));

        chatService.chat("hello", "default");

        verify(chatTitleGenerator).generate("user_1_chat_default", "hello");
    }

    @Test
    void chat_subsequentMessageDoesNotTriggerTitleGeneration() {
        ChatSession session = session("user_1_chat_default", 1);
        session.setTitle("existing title");
        when(chatSessionRepository.findById("user_1_chat_default"))
                .thenReturn(Optional.of(session));
        when(chatServiceClient.chat(any(), any(int.class), any()))
                .thenReturn(new ChatReplyVO("user_1_chat_default", "reply"));

        chatService.chat("hello", "default");

        verify(chatTitleGenerator, never()).generate(any(), any());
    }

    @Test
    void listSessions_returnsUserSessionsOrdered() {
        ChatSession s1 = session("user_1_chat_a", 1);
        s1.setUpdatedAt(LocalDateTime.of(2025, 1, 20, 10, 0));
        ChatSession s2 = session("user_1_chat_b", 1);
        s2.setUpdatedAt(LocalDateTime.of(2025, 1, 20, 9, 0));
        when(chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(1))
                .thenReturn(List.of(s1, s2));

        List<ChatSessionVO> result = chatService.listSessions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("user_1_chat_a");
        assertThat(result.get(1).id()).isEqualTo("user_1_chat_b");
    }

    @Test
    void getSessionMessages_validSession_returnsHistory() {
        ChatSession session = session("user_1_chat_default", 1);
        when(chatSessionRepository.findById("user_1_chat_default"))
                .thenReturn(Optional.of(session));
        List<ChatMessageVO> history =
                List.of(new ChatMessageVO("user", "hello"), new ChatMessageVO("assistant", "hi"));
        when(chatServiceClient.history("user_1_chat_default")).thenReturn(history);

        List<ChatMessageVO> result = chatService.getSessionMessages("user_1_chat_default");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo("user");
    }

    @Test
    void getSessionMessages_otherUsersSession_throws404() {
        ChatSession session = session("user_2_chat_default", 2);
        when(chatSessionRepository.findById("user_2_chat_default"))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> chatService.getSessionMessages("user_2_chat_default"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void getSessionMessages_missingSession_throws404() {
        when(chatSessionRepository.findById("user_1_chat_missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getSessionMessages("user_1_chat_missing"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void currentUserId_noAuthentication_throwsAuthException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> chatService.chat("hello", "default"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void chatStream_upstream5xx_completesWithErrorAndSkipsTitle() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/chat/stream",
                exchange -> {
                    byte[] body = "upstream failure".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            setField(
                    "properties",
                    new ChatServiceProperties(
                            "http://127.0.0.1:" + port, 1000, 5000, 10000, 3000));

            ChatSession session = session("user_1_chat_default", 1);
            session.setTitle(null);
            when(chatSessionRepository.findById("user_1_chat_default"))
                    .thenReturn(Optional.of(session));

            TrackingSseEmitter emitter = new TrackingSseEmitter(5000L);

            chatService.chatStream("hello", "default", emitter);

            assertThat(emitter.awaitDone(5, TimeUnit.SECONDS)).isTrue();
            assertThat(emitter.error.get()).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) emitter.error.get();
            assertThat(be.getStatus()).isEqualTo(502);
            verify(chatTitleGenerator, never()).generate(any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStream_upstream2xx_forwardsSseAndTriggersTitle() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/chat/stream",
                exchange -> {
                    String sse = "data: {\"content\":\"hi\"}\n\ndata: [DONE]\n\n";
                    byte[] body = sse.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            setField(
                    "properties",
                    new ChatServiceProperties(
                            "http://127.0.0.1:" + port, 1000, 5000, 10000, 3000));

            ChatSession session = session("user_1_chat_default", 1);
            session.setTitle(null);
            when(chatSessionRepository.findById("user_1_chat_default"))
                    .thenReturn(Optional.of(session));

            TrackingSseEmitter emitter = new TrackingSseEmitter(5000L);

            chatService.chatStream("hello", "default", emitter);

            assertThat(emitter.awaitDone(5, TimeUnit.SECONDS)).isTrue();
            assertThat(emitter.error.get()).isNull();
            verify(chatTitleGenerator, timeout(5000)).generate("user_1_chat_default", "hello");
        } finally {
            server.stop(0);
        }
    }

    private static ChatSession session(String id, int userId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(userId);
        session.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return session;
    }

    private void setField(String name, Object value) throws Exception {
        var field = ChatService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chatService, value);
    }

    private static final class TrackingSseEmitter extends SseEmitter {
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        TrackingSseEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void complete() {
            done.countDown();
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            error.set(ex);
            done.countDown();
            super.completeWithError(ex);
        }

        boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit);
        }
    }
}
