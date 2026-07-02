package dev.kaiwen.bikes.client;

import dev.kaiwen.bikes.dto.request.ChatRequestDTO;
import dev.kaiwen.bikes.dto.response.ChatMessageVO;
import dev.kaiwen.bikes.dto.response.ChatReplyVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatServiceClient {

    private final RestClient chatServiceRestClient;
    private final RestClient chatServiceTitleRestClient;

    public ChatReplyVO chat(
            String sessionId, int userId, String message, ChatRequestDTO.LocationDTO location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("user_id", userId);
        body.put("message", message);
        if (location != null) {
            body.put("location", location);
        }
        return chatServiceRestClient
                .post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatReplyVO.class);
    }

    public String title(String message) {
        record TitleResp(String title) {}
        TitleResp resp = chatServiceTitleRestClient
                .post()
                .uri("/chat/title")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message))
                .retrieve()
                .body(TitleResp.class);
        return resp != null ? resp.title() : null;
    }

    public List<ChatMessageVO> history(String sessionId) {
        return chatServiceRestClient
                .get()
                .uri("/sessions/{id}/messages", sessionId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
