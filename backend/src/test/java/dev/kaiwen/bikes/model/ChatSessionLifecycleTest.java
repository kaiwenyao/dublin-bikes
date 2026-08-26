package dev.kaiwen.bikes.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** {@link ChatSession} 的 JPA 生命周期回调（{@code @PrePersist}/{@code @PreUpdate}）单测。 */
class ChatSessionLifecycleTest {

    @Test
    void onCreate_nullCreatedAt_setsBothTimestamps() {
        ChatSession session = new ChatSession();

        session.onCreate();

        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isNotNull();
    }

    @Test
    void onCreate_presetCreatedAt_keepsItAndSetsUpdatedAt() {
        ChatSession session = new ChatSession();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 20, 10, 0);
        session.setCreatedAt(preset);

        session.onCreate();

        assertThat(session.getCreatedAt()).isEqualTo(preset);
        assertThat(session.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdate_refreshesUpdatedAt() {
        ChatSession session = new ChatSession();
        session.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        session.onUpdate();

        assertThat(session.getUpdatedAt())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}
