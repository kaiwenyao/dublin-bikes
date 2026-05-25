package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.dto.ApiResponse;
import dev.kaiwen.bikes.dto.request.ChatRequestDTO;
import dev.kaiwen.bikes.dto.response.ChatMessageVO;
import dev.kaiwen.bikes.dto.response.ChatReplyVO;
import dev.kaiwen.bikes.dto.response.ChatSessionVO;
import dev.kaiwen.bikes.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ApiResponse<ChatReplyVO> chat(@Valid @RequestBody ChatRequestDTO request) {
        return ApiResponse.ok(chatService.chat(request.message(), request.chatId()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(0L);
        chatService.chatStream(request.message(), request.chatId(), emitter);
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(emitter);
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionVO>> listSessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<ChatMessageVO>> getSessionMessages(@PathVariable("id") String sessionId) {
        return ApiResponse.ok(chatService.getSessionMessages(sessionId));
    }
}
