package dev.kaiwen.bikes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-input failures (malformed JSON, invalid path types, unsupported
 * media types, wrong HTTP methods) must be reported with 4xx status codes,
 * not masked as the generic 500 "service temporarily unavailable" (which
 * pollutes error logs and misleads the frontend into offering a retry).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    private MockMvc handlerMockMvc;

    @RestController
    static class MethodProbeController {
        @GetMapping("/probe")
        public String probe() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        handlerMockMvc = MockMvcBuilders.standaloneSetup(new MethodProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void malformedJson_returns400Not500() throws Exception {
        mockMvc.perform(
                        post("/api/users/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"u@example.com\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void pathVariableTypeMismatch_returns400Not500() throws Exception {
        mockMvc.perform(get("/api/stations/not-a-number/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void unsupportedContentType_returns415Not500() throws Exception {
        mockMvc.perform(
                        post("/api/users/login")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("identifier=u@example.com&password=password12"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void wrongHttpMethod_returns405FromHandlerNot500() throws Exception {
        // Spring Security intercepts unmatched methods on real endpoints (401
        // login-wall), so this verifies the handler itself maps
        // HttpRequestMethodNotSupportedException to 405, not a generic 500.
        handlerMockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40501));
    }
}