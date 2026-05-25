package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.client.ChatServiceClient;
import dev.kaiwen.bikes.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTitleGenerator {

    private final ChatServiceClient chatServiceClient;
    private final ChatSessionRepository chatSessionRepository;

    @Async("mailExecutor")
    public void generate(String sessionId, String message) {
        try {
            String title = chatServiceClient.title(message);
            if (title == null) {
                return;
            }
            chatSessionRepository.findById(sessionId).ifPresent(session -> {
                session.setTitle(title);
                chatSessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("title generation failed for session {}", sessionId, e);
        }
    }
}
