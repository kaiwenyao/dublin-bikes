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
        boolean hasStartAddress = hasText(dto.startAddress());
        boolean hasEndAddress = hasText(dto.endAddress());
        boolean hasStartCoord = dto.start() != null;
        boolean hasEndCoord = dto.end() != null;

        boolean anyAddress = hasStartAddress || hasEndAddress;
        boolean anyCoord = hasStartCoord || hasEndCoord;

        if (anyAddress && anyCoord) {
            throw new BusinessException(
                    ApiCodes.VALIDATION_ERROR,
                    "provide either start_address/end_address or start/end coordinates, not both",
                    400);
        }
        if (anyAddress) {
            if (!hasStartAddress || !hasEndAddress) {
                throw new BusinessException(
                        ApiCodes.VALIDATION_ERROR,
                        "start_address and end_address are both required",
                        400);
            }
            return;
        }
        if (anyCoord) {
            if (!hasStartCoord || !hasEndCoord) {
                throw new BusinessException(
                        ApiCodes.VALIDATION_ERROR,
                        "start and end coordinates are both required",
                        400);
            }
            if (dto.start().lat() == null
                    || dto.start().lon() == null
                    || dto.end().lat() == null
                    || dto.end().lon() == null) {
                throw new BusinessException(
                        ApiCodes.VALIDATION_ERROR, "start and end coordinates are required", 400);
            }
            return;
        }
        throw new BusinessException(
                ApiCodes.VALIDATION_ERROR,
                "provide either start_address/end_address or start/end coordinates",
                400);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
