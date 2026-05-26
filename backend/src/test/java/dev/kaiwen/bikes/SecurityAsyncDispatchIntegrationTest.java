package dev.kaiwen.bikes;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityAsyncDispatchIntegrationTest.TestSseController.class)
class SecurityAsyncDispatchIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void protectedSseStillRequiresAuthenticationOnInitialRequest() throws Exception {
        mockMvc.perform(post("/api/chat/test-async-dispatch")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedSseAllowsAsyncRedispatchAfterAuthentication() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/chat/test-async-dispatch")
                        .with(user("tester").roles("USER"))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:ok")));
    }

    @RestController
    static class TestSseController {
        @PostMapping(path = "/api/chat/test-async-dispatch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            SseEmitter emitter = new SseEmitter(3000L);
            CompletableFuture.runAsync(() -> {
                try {
                    emitter.send(SseEmitter.event().data("ok"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            });
            return emitter;
        }
    }
}
