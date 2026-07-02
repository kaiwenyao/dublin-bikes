package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.config.InternalAiToolProperties;
import dev.kaiwen.bikes.dto.response.InternalCurrentUserVO;
import dev.kaiwen.bikes.service.InternalAiToolService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/internal/ai/tools")
@RequiredArgsConstructor
public class InternalAiToolController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final InternalAiToolService internalAiToolService;
    private final InternalAiToolProperties properties;

    @GetMapping("/current-user")
    public InternalCurrentUserVO currentUser(
            @RequestParam("session_id") @NotBlank @Size(max = 64) String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireServiceToken(authorization);
        return internalAiToolService.currentUserForSession(sessionId);
    }

    private void requireServiceToken(String authorization) {
        String configuredToken = properties.serviceToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "internal AI tools are not configured");
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid service token");
        }
        String providedToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!constantTimeEquals(configuredToken, providedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid service token");
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
