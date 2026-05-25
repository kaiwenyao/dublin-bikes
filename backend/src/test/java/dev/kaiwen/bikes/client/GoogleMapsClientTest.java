package dev.kaiwen.bikes.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
