package dev.kaiwen.bikes.dto.request;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.exception.BusinessException;

public final class JourneyRequestNormalizer {

    private JourneyRequestNormalizer() {}

    public static JourneyRequestDTO normalize(JourneyRequestDTO dto) {
        validate(dto);
        return dto;
    }

    private static void validate(JourneyRequestDTO dto) {
        boolean addressMode = hasText(dto.startAddress()) && hasText(dto.endAddress());
        boolean coordMode = dto.start() != null && dto.end() != null;
        if (addressMode == coordMode) {
            throw new BusinessException(
                    ApiCodes.VALIDATION_ERROR,
                    "provide either start_address/end_address or start/end coordinates",
                    400);
        }
        if (coordMode) {
            if (dto.start().lat() == null
                    || dto.start().lon() == null
                    || dto.end().lat() == null
                    || dto.end().lon() == null) {
                throw new BusinessException(
                        ApiCodes.VALIDATION_ERROR, "start and end coordinates are required", 400);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
