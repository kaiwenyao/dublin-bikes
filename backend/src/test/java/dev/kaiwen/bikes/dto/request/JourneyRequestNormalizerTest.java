package dev.kaiwen.bikes.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.exception.BusinessException;
import org.junit.jupiter.api.Test;

class JourneyRequestNormalizerTest {

    @Test
    void normalize_acceptsAddressPair() {
        JourneyRequestDTO dto =
                new JourneyRequestDTO("A St", "B St", null, null);

        assertThat(JourneyRequestNormalizer.normalize(dto)).isSameAs(dto);
    }

    @Test
    void normalize_acceptsCoordinatePair() {
        JourneyRequestDTO dto =
                new JourneyRequestDTO(
                        null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        assertThat(JourneyRequestNormalizer.normalize(dto)).isSameAs(dto);
    }

    @Test
    void normalize_rejectsMixedAddressAndCoordinates() {
        JourneyRequestDTO dto =
                new JourneyRequestDTO(
                        "A St",
                        "B St",
                        new GeoPointDTO(53.34, -6.26),
                        new GeoPointDTO(53.33, -6.25));

        assertValidationError(dto, "not both");
    }

    @Test
    void normalize_rejectsCoordinatesWithSingleAddress() {
        JourneyRequestDTO dto =
                new JourneyRequestDTO(
                        "A St",
                        null,
                        new GeoPointDTO(53.34, -6.26),
                        new GeoPointDTO(53.33, -6.25));

        assertValidationError(dto, "not both");
    }

    @Test
    void normalize_rejectsPartialAddressPair() {
        JourneyRequestDTO dto = new JourneyRequestDTO("A St", null, null, null);

        assertValidationError(dto, "both required");
    }

    @Test
    void normalize_rejectsPartialCoordinatePair() {
        JourneyRequestDTO dto =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), null);

        assertValidationError(dto, "both required");
    }

    @Test
    void normalize_rejectsEmptyPayload() {
        JourneyRequestDTO dto = new JourneyRequestDTO(null, null, null, null);

        assertValidationError(dto, "provide either");
    }

    private static void assertValidationError(JourneyRequestDTO dto, String messageFragment) {
        assertThatThrownBy(() -> JourneyRequestNormalizer.normalize(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.VALIDATION_ERROR);
                            assertThat(be.getStatus()).isEqualTo(400);
                            assertThat(be.getMessage()).contains(messageFragment);
                        });
    }
}
