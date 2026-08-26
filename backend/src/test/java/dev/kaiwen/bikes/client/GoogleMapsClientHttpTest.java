package dev.kaiwen.bikes.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.kaiwen.bikes.config.GoogleMapsProperties;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.exception.BusinessException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link GoogleMapsClient} 的 HTTP 层测试：用 {@link MockRestServiceServer} 拦截 RestClient
 * 的出站请求并回放固定响应，覆盖 geocode / distanceMatrix 的请求构造与响应解析。
 * （纯单元测试，无 Spring 上下文，{@code @Retry} 切面不生效，异常直接向上抛。）
 */
class GoogleMapsClientHttpTest {

    private static final GoogleMapsProperties PROPS =
            new GoogleMapsProperties("test-api-key", 1000, 1000, 0);

    private MockRestServiceServer server;
    private GoogleMapsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleMapsClient(builder.build(), PROPS);
    }

    @Test
    void geocode_ok_parsesLatLon() {
        server.expect(requestTo(Matchers.containsString("/maps/api/geocode/json")))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":{\"lat\":53.34,\"lng\":-6.26}}}]}",
                                MediaType.APPLICATION_JSON));

        LatLon result = client.geocode("Dublin");

        assertThat(result.lat()).isEqualTo(53.34);
        assertThat(result.lon()).isEqualTo(-6.26);
        server.verify();
    }

    @Test
    void geocode_okButMissingLocation_throwsAddressNotResolved() {
        server.expect(requestTo(Matchers.containsString("/maps/api/geocode/json")))
                .andRespond(
                        withSuccess("{\"status\":\"OK\",\"results\":[{}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.geocode("Nowhere"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.ADDRESS_NOT_RESOLVED);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void geocode_emptyResponseBody_throwsTransient() {
        server.expect(requestTo(Matchers.containsString("/maps/api/geocode/json")))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.geocode("Dublin"))
                .isInstanceOf(GoogleMapsTransientException.class);
    }

    @Test
    void geocode_nullStatusField_throwsTransient() {
        server.expect(requestTo(Matchers.containsString("/maps/api/geocode/json")))
                .andRespond(withSuccess("{\"status\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.geocode("Dublin"))
                .isInstanceOf(GoogleMapsTransientException.class)
                .hasMessageContaining("geocode status");
    }

    @Test
    void geocode_blankApiKey_throwsUnavailableWithoutHttpCall() {
        GoogleMapsClient noKeyClient =
                new GoogleMapsClient(
                        RestClient.builder().baseUrl("http://localhost").build(),
                        new GoogleMapsProperties(" ", 1000, 1000, 0));

        assertThatThrownBy(() -> noKeyClient.geocode("Dublin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(500);
                        });
        server.verify();
    }

    @Test
    void distanceMatrix_ok_parsesDurationsAndMarksUnreachable() {
        server.expect(requestTo(Matchers.containsString("/maps/api/distancematrix/json")))
                .andRespond(
                        withSuccess(
                                """
                                {"status":"OK","rows":[
                                  {"elements":[
                                    {"status":"OK","duration":{"value":120}},
                                    {"status":"ZERO_RESULTS"}]},
                                  {"elements":[
                                    {"status":"OK","duration":{"value":300}},
                                    {"status":"OK","duration":{"value":450}}]}]}
                                """,
                                MediaType.APPLICATION_JSON));

        List<LatLon> origins = List.of(new LatLon(53.34, -6.26), new LatLon(53.35, -6.27));
        List<LatLon> destinations = List.of(new LatLon(53.33, -6.25), new LatLon(53.32, -6.24));

        int[][] matrix = client.distanceMatrix(origins, destinations, "walking");

        assertThat(matrix)
                .isDeepEqualTo(
                        new int[][] {{120, GoogleMapsClient.UNREACHABLE_DURATION}, {300, 450}});
        server.verify();
    }

    @Test
    void distanceMatrix_emptyOrigins_returnsEmptyWithoutHttpCall() {
        int[][] matrix =
                client.distanceMatrix(List.of(), List.of(new LatLon(53.33, -6.25)), "walking");

        assertThat(matrix).isDeepEqualTo(new int[0][0]);
        server.verify();
    }

    @Test
    void distanceMatrix_nonOkStatus_throwsUnavailable() {
        server.expect(requestTo(Matchers.containsString("/maps/api/distancematrix/json")))
                .andRespond(
                        withSuccess("{\"status\":\"REQUEST_DENIED\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                client.distanceMatrix(
                                        List.of(new LatLon(53.34, -6.26)),
                                        List.of(new LatLon(53.33, -6.25)),
                                        "bicycling"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(500);
                        });
    }
}
