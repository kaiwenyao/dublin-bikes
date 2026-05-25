package dev.kaiwen.bikes.client;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kaiwen.bikes.config.GoogleMapsProperties;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.exception.BusinessException;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleMapsClient {

    public static final int UNREACHABLE_DURATION = Integer.MAX_VALUE;

    private final RestClient googleMapsRestClient;
    private final GoogleMapsProperties properties;

    @Retry(name = "gmaps", fallbackMethod = "geocodeFallback")
    public LatLon geocode(String address) {
        requireApiKey();
        JsonNode root =
                googleMapsRestClient
                        .get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .path("/maps/api/geocode/json")
                                                .queryParam("address", address)
                                                .queryParam("key", properties.apiKey())
                                                .build())
                        .retrieve()
                        .body(JsonNode.class);
        return parseGeocodeResponse(root);
    }

    @SuppressWarnings("unused")
    private LatLon geocodeFallback(String address, Throwable ex) {
        if (ex instanceof BusinessException businessException) {
            throw businessException;
        }
        log.warn("Google geocode failed for address={}", address, ex);
        throw mapsUnavailable();
    }

    static LatLon parseGeocodeResponse(JsonNode root) {
        if (root == null) {
            throw new GoogleMapsTransientException("empty geocode response");
        }
        String status = text(root, "status");
        if ("OK".equals(status)) {
            JsonNode location = root.path("results").path(0).path("geometry").path("location");
            if (location.isMissingNode()) {
                throw addressNotResolved();
            }
            return new LatLon(location.path("lat").asDouble(), location.path("lng").asDouble());
        }
        if ("ZERO_RESULTS".equals(status)) {
            throw addressNotResolved();
        }
        if ("INVALID_REQUEST".equals(status)) {
            throw new BusinessException(ApiCodes.VALIDATION_ERROR, "invalid address", 400);
        }
        throw new GoogleMapsTransientException("geocode status: " + status);
    }

    @Retry(name = "gmaps", fallbackMethod = "distanceMatrixFallback")
    public int[][] distanceMatrix(List<LatLon> origins, List<LatLon> destinations, String mode) {
        requireApiKey();
        if (origins.isEmpty() || destinations.isEmpty()) {
            return new int[0][0];
        }
        JsonNode root =
                googleMapsRestClient
                        .get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .path("/maps/api/distancematrix/json")
                                                .queryParam("origins", joinCoords(origins))
                                                .queryParam("destinations", joinCoords(destinations))
                                                .queryParam("mode", mode)
                                                .queryParam("key", properties.apiKey())
                                                .build())
                        .retrieve()
                        .body(JsonNode.class);
        if (root == null || !"OK".equals(text(root, "status"))) {
            throw mapsUnavailable();
        }
        JsonNode rows = root.path("rows");
        int[][] result = new int[origins.size()][destinations.size()];
        for (int i = 0; i < origins.size(); i++) {
            JsonNode elements = rows.path(i).path("elements");
            for (int j = 0; j < destinations.size(); j++) {
                JsonNode element = elements.path(j);
                if ("OK".equals(text(element, "status"))) {
                    result[i][j] = element.path("duration").path("value").asInt(UNREACHABLE_DURATION);
                } else {
                    result[i][j] = UNREACHABLE_DURATION;
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unused")
    private int[][] distanceMatrixFallback(
            List<LatLon> origins, List<LatLon> destinations, String mode, Throwable ex) {
        log.warn("Google distance matrix failed mode={}", mode, ex);
        throw mapsUnavailable();
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw mapsUnavailable();
        }
    }

    private static String joinCoords(List<LatLon> points) {
        return points.stream().map(LatLon::toGoogleParam).collect(Collectors.joining("|"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BusinessException addressNotResolved() {
        return new BusinessException(ApiCodes.NO_AVAILABLE_ROUTE, "no available route", 404);
    }

    private static BusinessException mapsUnavailable() {
        return new BusinessException(
                ApiCodes.GENERIC_ERROR,
                "Service temporarily unavailable, please try again later",
                500);
    }
}
