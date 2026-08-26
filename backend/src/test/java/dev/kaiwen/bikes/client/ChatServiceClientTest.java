package dev.kaiwen.bikes.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.kaiwen.bikes.dto.response.ChatMessageVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@link ChatServiceClient} 的 HTTP 层测试：标题生成与历史消息两个端点。 */
class ChatServiceClientTest {

    private MockRestServiceServer mainServer;
    private MockRestServiceServer titleServer;
    private ChatServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder mainBuilder = RestClient.builder().baseUrl("http://localhost");
        mainServer = MockRestServiceServer.bindTo(mainBuilder).build();
        RestClient.Builder titleBuilder = RestClient.builder().baseUrl("http://localhost");
        titleServer = MockRestServiceServer.bindTo(titleBuilder).build();
        client = new ChatServiceClient(mainBuilder.build(), titleBuilder.build());
    }

    @Test
    void title_ok_returnsTitle() {
        titleServer
                .expect(requestTo("http://localhost/chat/title"))
                .andRespond(withSuccess("{\"title\":\"Bike to work\"}", MediaType.APPLICATION_JSON));

        assertThat(client.title("how do I get to work")).isEqualTo("Bike to work");
        titleServer.verify();
    }

    @Test
    void title_emptyBody_returnsNull() {
        titleServer.expect(requestTo("http://localhost/chat/title")).andRespond(withSuccess());

        assertThat(client.title("hello")).isNull();
    }

    @Test
    void history_ok_returnsMessages() {
        mainServer
                .expect(requestTo("http://localhost/sessions/user_1_chat_default/messages"))
                .andRespond(
                        withSuccess(
                                "[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"hello\"}]",
                                MediaType.APPLICATION_JSON));

        List<ChatMessageVO> history = client.history("user_1_chat_default");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(1).content()).isEqualTo("hello");
        mainServer.verify();
    }
}
