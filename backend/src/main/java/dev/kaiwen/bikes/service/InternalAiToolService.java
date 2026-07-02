package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.dto.response.InternalCurrentUserVO;
import dev.kaiwen.bikes.model.ChatSession;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.ChatSessionRepository;
import dev.kaiwen.bikes.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InternalAiToolService {

    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public InternalCurrentUserVO currentUserForSession(String sessionId) {
        ChatSession session = chatSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
        User user = userRepository
                .findById(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        return new InternalCurrentUserVO(user.getId(), user.getEmail());
    }
}
