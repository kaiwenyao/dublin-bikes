package dev.kaiwen.bikes.dto.response;

public record StationVO(
        int number,
        String contractName,
        String name,
        String address,
        float latitude,
        float longitude,
        boolean banking,
        boolean bonus,
        int bikeStands) {}
