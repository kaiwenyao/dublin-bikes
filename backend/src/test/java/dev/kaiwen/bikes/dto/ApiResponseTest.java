package dev.kaiwen.bikes.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void ok_withCustomMessage_usesIt() {
        ApiResponse<String> resp = ApiResponse.ok("done", "payload");

        assertThat(resp.code()).isEqualTo(ApiCodes.SUCCESS);
        assertThat(resp.msg()).isEqualTo("done");
        assertThat(resp.data()).isEqualTo("payload");
    }

    @Test
    void error_carriesCodeAndMessageWithNullData() {
        ApiResponse<Void> resp = ApiResponse.error(ApiCodes.VALIDATION_ERROR, "bad");

        assertThat(resp.code()).isEqualTo(ApiCodes.VALIDATION_ERROR);
        assertThat(resp.msg()).isEqualTo("bad");
        assertThat(resp.data()).isNull();
    }
}
