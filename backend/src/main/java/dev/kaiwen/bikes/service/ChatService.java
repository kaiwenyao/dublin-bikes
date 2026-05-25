package dev.kaiwen.bikes.service;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SESSION_PREFIX = "user_%d_chat_";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatServiceClient chatServiceClient;
    private final ChatTitleGenerator chatTitleGenerator;
    private final ChatServiceProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatReplyVO chat(String message, String chatId) {
        int userId = currentUserId();
        String sessionId = generateSessionId(userId, chatId);
        ChatSession session = ensureSession(sessionId, userId);
        ChatReplyVO reply = chatServiceClient.chat(sessionId, userId, message);
        if (session.getTitle() == null) {
            chatTitleGenerator.generate(sessionId, message);
        }
        return reply;
    }

    public void chatStream(String message, String chatId, SseEmitter emitter) {
        int userId = currentUserId();
        String sessionId = generateSessionId(userId, chatId);
        ensureSession(sessionId, userId);

        boolean needsTitle = chatSessionRepository.findById(sessionId)
                .map(s -> s.getTitle() == null)
                .orElse(false);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(
                    Map.of("session_id", sessionId, "user_id", userId, "message", message));
        } catch (Exception e) {
            log.error("failed to serialize chat stream request", e);
            emitter.completeWithError(new BusinessException(
                    ApiCodes.GENERIC_ERROR, "failed to serialize request", 500));
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofMillis(properties.streamTimeoutMs()))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        String body = String.join("\n", response.body().toList());
                        log.error("chat stream upstream returned status {}: {}", status, body);
                        emitter.completeWithError(new BusinessException(
                                ApiCodes.GENERIC_ERROR,
                                "chat service unavailable",
                                mapUpstreamStatus(status)));
                        return;
                    }
                    response.body().forEach(line -> {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            try {
                                emitter.send(SseEmitter.event().data(data));
                            } catch (Exception e) {
                                log.debug("sse emitter send failed (client likely disconnected)", e);
                            }
                        }
                    });
                    emitter.complete();
                    if (needsTitle) {
                        chatTitleGenerator.generate(sessionId, message);
                    }
                })
                .exceptionally(ex -> {
                    log.error("chat stream failed", ex);
                    emitter.completeWithError(ex);
                    return null;
                });
    }

    @Transactional(readOnly = true)
    public List<ChatSessionVO> listSessions() {
        int userId = currentUserId();
        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageVO> getSessionMessages(String sessionId) {
        int userId = currentUserId();
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ApiCodes.GENERIC_ERROR, "session not found", 404));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCodes.GENERIC_ERROR, "session not found", 404);
        }
        return chatServiceClient.history(sessionId);
    }

    ChatSession ensureSession(String sessionId, int userId) {
        Optional<ChatSession> existing = chatSessionRepository.findById(sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setUserId(userId);
        try {
            return chatSessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            return chatSessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(
                            ApiCodes.GENERIC_ERROR, "session creation failed", 500));
        }
    }

    String generateSessionId(int userId, String chatId) {
        if (chatId == null || chatId.isBlank()) {
            chatId = "default";
        }
        String prefix = String.format(SESSION_PREFIX, userId);
        String candidate = prefix + chatId;
        boolean validChatId = chatId.matches("[A-Za-z0-9_.\\-]+");
        if (candidate.length() <= 64 && validChatId) {
            return candidate;
        }
        return prefix + "h_" + sha256Hex(chatId).substring(0, 32);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private ChatSessionVO toVO(ChatSession session) {
        return new ChatSessionVO(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt() != null ? session.getCreatedAt().format(ISO_FMT) : null,
                session.getUpdatedAt() != null ? session.getUpdatedAt().format(ISO_FMT) : null);
    }

    private static int mapUpstreamStatus(int upstreamStatus) {
        if (upstreamStatus >= 500) {
            return 502;
        }
        if (upstreamStatus >= 400) {
            return upstreamStatus;
        }
        return 500;
    }

    private static int currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new AuthException("unauthorized");
        }
        return principal.userId();
    }
}
