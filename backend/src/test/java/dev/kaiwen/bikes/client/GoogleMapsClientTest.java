package dev.kaiwen.bikes.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.exception.BusinessException;
import org.junit.jupiter.api.Test;

class GoogleMapsClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseGeocodeResponse_ok_returnsLatLon() throws Exception {
        String json =
                """
                {"status":"OK","results":[{"geometry":{"location":{"lat":53.34,"lng":-6.26}}}]}
                """;

        LatLon latLon = GoogleMapsClient.parseGeocodeResponse(objectMapper.readTree(json));

        assertThat(latLon.lat()).isEqualTo(53.34);
        assertThat(latLon.lon()).isEqualTo(-6.26);
    }

    @Test
    void parseGeocodeResponse_zeroResults_returns404() throws Exception {
        assertGeocodeError("{\"status\":\"ZERO_RESULTS\"}", ApiCodes.ADDRESS_NOT_RESOLVED, 404);
    }

    @Test
    void parseGeocodeResponse_invalidRequest_returns400() throws Exception {
        assertGeocodeError("{\"status\":\"INVALID_REQUEST\"}", ApiCodes.VALIDATION_ERROR, 400);
    }

    @Test
    void parseGeocodeResponse_unknownStatus_isTransient() throws Exception {
        assertThatThrownBy(
                        () ->
                                GoogleMapsClient.parseGeocodeResponse(
                                        objectMapper.readTree("{\"status\":\"UNKNOWN_ERROR\"}")))
                .isInstanceOf(GoogleMapsTransientException.class);
    }

    private void assertGeocodeError(String json, int expectedCode, int expectedStatus) throws Exception {
        assertThatThrownBy(
                        () -> GoogleMapsClient.parseGeocodeResponse(objectMapper.readTree(json)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(expectedCode);
                            assertThat(be.getStatus()).isEqualTo(expectedStatus);
                        });
    }

    // ---- distance matrix response validation ----

    @Test
    void validateDistanceMatrixResponse_ok_doesNotThrow() throws Exception {
        JsonNode root = objectMapper.readTree("{\"status\":\"OK\",\"rows\":[]}");
        GoogleMapsClient.validateDistanceMatrixResponse(root);
    }

    @Test
    void validateDistanceMatrixResponse_requestDenied_throwsWithDetail() throws Exception {
        String json =
                """
                {"status":"REQUEST_DENIED","error_message":"API keys with referer restrictions cannot be used with this API."}
                """;
        assertThatThrownBy(
                        () -> GoogleMapsClient.validateDistanceMatrixResponse(objectMapper.readTree(json)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(502);
                            assertThat(be.getMessage())
                                    .contains("referer restrictions");
                        });
    }

    @Test
    void validateDistanceMatrixResponse_requestDenied_noMessage_usesDefault() throws Exception {
        assertThatThrownBy(
                        () ->
                                GoogleMapsClient.validateDistanceMatrixResponse(
                                        objectMapper.readTree("{\"status\":\"REQUEST_DENIED\"}")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getStatus()).isEqualTo(502);
                            assertThat(be.getMessage()).contains("API key or permissions issue");
                        });
    }

    @Test
    void validateDistanceMatrixResponse_overQueryLimit_isTransient() throws Exception {
        assertThatThrownBy(
                        () ->
                                GoogleMapsClient.validateDistanceMatrixResponse(
                                        objectMapper.readTree("{\"status\":\"OVER_QUERY_LIMIT\"}")))
                .isInstanceOf(GoogleMapsTransientException.class);
    }

    @Test
    void validateDistanceMatrixResponse_invalidRequest_returns400() throws Exception {
        assertThatThrownBy(
                        () ->
                                GoogleMapsClient.validateDistanceMatrixResponse(
                                        objectMapper.readTree("{\"status\":\"INVALID_REQUEST\"}")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.VALIDATION_ERROR);
                            assertThat(be.getStatus()).isEqualTo(400);
                        });
    }

    @Test
    void validateDistanceMatrixResponse_unknownStatus_isTransient() throws Exception {
        assertThatThrownBy(
                        () ->
                                GoogleMapsClient.validateDistanceMatrixResponse(
                                        objectMapper.readTree("{\"status\":\"UNKNOWN\"}")))
                .isInstanceOf(GoogleMapsTransientException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
