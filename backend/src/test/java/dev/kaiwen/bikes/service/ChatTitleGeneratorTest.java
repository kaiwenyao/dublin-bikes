package dev.kaiwen.bikes.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.client.ChatServiceClient;
import dev.kaiwen.bikes.model.ChatSession;
import dev.kaiwen.bikes.repository.ChatSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatTitleGeneratorTest {

    @Mock private ChatServiceClient chatServiceClient;
    @Mock private ChatSessionRepository chatSessionRepository;

    @InjectMocks private ChatTitleGenerator chatTitleGenerator;

    @Test
    void generate_nullTitle_doesNotTouchRepository() {
        when(chatServiceClient.title("hello")).thenReturn(null);

        chatTitleGenerator.generate("user_1_chat_default", "hello");

        verify(chatSessionRepository, never()).findById(any());
        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    void generate_titleAndSessionPresent_updatesTitle() {
        when(chatServiceClient.title("hello")).thenReturn("Greeting chat");
        ChatSession session = new ChatSession();
        session.setId("user_1_chat_default");
        session.setUserId(1);
        when(chatSessionRepository.findById("user_1_chat_default"))
                .thenReturn(Optional.of(session));

        chatTitleGenerator.generate("user_1_chat_default", "hello");

        verify(chatSessionRepository).save(session);
        org.assertj.core.api.Assertions.assertThat(session.getTitle()).isEqualTo("Greeting chat");
    }

    @Test
    void generate_sessionMissing_doesNotSave() {
        when(chatServiceClient.title("hello")).thenReturn("Greeting chat");
        when(chatSessionRepository.findById("user_1_chat_missing")).thenReturn(Optional.empty());

        chatTitleGenerator.generate("user_1_chat_missing", "hello");

        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    void generate_clientThrows_isSwallowed() {
        when(chatServiceClient.title("hello")).thenThrow(new RuntimeException("upstream down"));

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> chatTitleGenerator.generate("user_1_chat_default", "hello"))
                .doesNotThrowAnyException();
        verify(chatSessionRepository, never()).save(any());
    }
}
