package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.client.GoogleMapsClient;
import dev.kaiwen.bikes.client.LatLon;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.request.GeoPointDTO;
import dev.kaiwen.bikes.dto.request.JourneyRequestDTO;
import dev.kaiwen.bikes.dto.response.GeoCoordinateVO;
import dev.kaiwen.bikes.dto.response.JourneyCyclingRouteVO;
import dev.kaiwen.bikes.dto.response.JourneyPlanResponseVO;
import dev.kaiwen.bikes.dto.response.JourneyRouteInfoVO;
import dev.kaiwen.bikes.dto.response.JourneySearchContextVO;
import dev.kaiwen.bikes.dto.response.JourneyStationLegVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import dev.kaiwen.bikes.util.DistanceUtils;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JourneyService {

    private static final int HAVERSINE_CANDIDATES = 10;
    private static final int MATRIX_SHORTLIST = 5;
    private static final String STATUS_OPEN = "OPEN";

    private final StationRepository stationRepository;
    private final AvailabilityRepository availabilityRepository;
    private final GoogleMapsClient googleMapsClient;

    public JourneyPlanResponseVO plan(JourneyRequestDTO request) {
        LatLon startResolved = resolveStart(request);
        LatLon endResolved = resolveEnd(request);

        Map<Integer, Station> stationsByNumber =
                stationRepository.findAllByOrderByNumberAsc().stream()
                        .collect(Collectors.toMap(Station::getNumber, Function.identity()));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime lookupSince = now.minusHours(1);
        LocalDateTime freshSince = now.minusMinutes(30);

        List<StationAvailability> eligible =
                availabilityRepository.findLatestPerStationSince(lookupSince).stream()
                        .filter(
                                a ->
                                        STATUS_OPEN.equals(a.getStatus())
                                                && !a.getTimestamp().isBefore(freshSince))
                        .map(a -> new StationAvailability(stationsByNumber.get(a.getNumber()), a))
                        .filter(sa -> sa.station() != null)
                        .toList();

        List<StationAvailability> startCandidates =
                eligible.stream()
                        .filter(sa -> sa.availability().getAvailableBikes() > 0)
                        .sorted(
                                Comparator.comparingDouble(
                                        sa ->
                                                DistanceUtils.haversineKm(
                                                        startResolved.lat(),
                                                        startResolved.lon(),
                                                        sa.station().getLatitude(),
                                                        sa.station().getLongitude())))
                        .limit(HAVERSINE_CANDIDATES)
                        .toList();

        List<StationAvailability> endCandidates =
                eligible.stream()
                        .filter(sa -> sa.availability().getAvailableBikeStands() > 0)
                        .sorted(
                                Comparator.comparingDouble(
                                        sa ->
                                                DistanceUtils.haversineKm(
                                                        endResolved.lat(),
                                                        endResolved.lon(),
                                                        sa.station().getLatitude(),
                                                        sa.station().getLongitude())))
                        .limit(HAVERSINE_CANDIDATES)
                        .toList();

        if (startCandidates.isEmpty() || endCandidates.isEmpty()) {
            throw noRoute();
        }

        RankedCandidates topStarts = rankByWalking(startResolved, startCandidates);
        RankedCandidates topEnds = rankByWalking(endResolved, endCandidates);

        if (topStarts.candidates().isEmpty() || topEnds.candidates().isEmpty()) {
            throw noRoute();
        }

        List<LatLon> startCoords =
                topStarts.candidates().stream().map(this::stationCoord).toList();
        List<LatLon> endCoords = topEnds.candidates().stream().map(this::stationCoord).toList();

        int[][] cycleMatrix =
                googleMapsClient.distanceMatrix(startCoords, endCoords, "bicycling");

        int[][] walkFromEndStations =
                googleMapsClient.distanceMatrix(endCoords, List.of(endResolved), "walking");

        BestRoute best = findBestRoute(topStarts, topEnds, cycleMatrix, walkFromEndStations);
        if (best == null) {
            throw noRoute();
        }

        StationAvailability start = topStarts.candidates().get(best.startIndex());
        StationAvailability end = topEnds.candidates().get(best.endIndex());

        JourneyStationLegVO startLeg =
                new JourneyStationLegVO(
                        start.station().getNumber(),
                        start.station().getName(),
                        start.station().getAddress(),
                        toCoord(start.station()),
                        best.walkToStart(),
                        start.availability().getAvailableBikes(),
                        null);

        JourneyStationLegVO endLeg =
                new JourneyStationLegVO(
                        end.station().getNumber(),
                        end.station().getName(),
                        end.station().getAddress(),
                        toCoord(end.station()),
                        best.walkFromEnd(),
                        null,
                        end.availability().getAvailableBikeStands());

        JourneyRouteInfoVO routeInfo =
                new JourneyRouteInfoVO(
                        startLeg,
                        endLeg,
                        new JourneyCyclingRouteVO(best.cycleTime()),
                        best.totalDuration());

        JourneySearchContextVO searchContext =
                new JourneySearchContextVO(
                        new GeoCoordinateVO(startResolved.lat(), startResolved.lon()),
                        new GeoCoordinateVO(endResolved.lat(), endResolved.lon()));

        return new JourneyPlanResponseVO(routeInfo, searchContext);
    }

    private LatLon resolveStart(JourneyRequestDTO request) {
        if (request.start() != null) {
            return fromGeoPoint(request.start());
        }
        return googleMapsClient.geocode(request.startAddress());
    }

    private LatLon resolveEnd(JourneyRequestDTO request) {
        if (request.end() != null) {
            return fromGeoPoint(request.end());
        }
        return googleMapsClient.geocode(request.endAddress());
    }

    private static LatLon fromGeoPoint(GeoPointDTO point) {
        return new LatLon(point.lat(), point.lon());
    }

    private RankedCandidates rankByWalking(LatLon origin, List<StationAvailability> candidates) {
        List<LatLon> destinations = candidates.stream().map(this::stationCoord).toList();
        int[][] matrix = googleMapsClient.distanceMatrix(List.of(origin), destinations, "walking");
        if (matrix.length == 0) {
            return new RankedCandidates(List.of(), List.of());
        }
        List<IndexedDuration> ranked = new ArrayList<>();
        for (int j = 0; j < candidates.size(); j++) {
            int duration = matrix[0][j];
            if (duration != GoogleMapsClient.UNREACHABLE_DURATION) {
                ranked.add(new IndexedDuration(j, duration));
            }
        }
        ranked.sort(Comparator.comparingInt(IndexedDuration::duration));
        List<StationAvailability> shortlist = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        for (IndexedDuration item : ranked.stream().limit(MATRIX_SHORTLIST).toList()) {
            shortlist.add(candidates.get(item.index()));
            durations.add(item.duration());
        }
        return new RankedCandidates(shortlist, durations);
    }

    private BestRoute findBestRoute(
            RankedCandidates starts,
            RankedCandidates ends,
            int[][] cycleMatrix,
            int[][] walkFromEndStations) {
        Integer bestTotal = null;
        int bestStart = -1;
        int bestEnd = -1;
        int bestCycle = 0;
        int bestWalkToStart = 0;
        int bestWalkFromEnd = 0;

        for (int i = 0; i < starts.candidates().size(); i++) {
            for (int j = 0; j < ends.candidates().size(); j++) {
                Station startStation = starts.candidates().get(i).station();
                Station endStation = ends.candidates().get(j).station();
                if (startStation.getNumber().equals(endStation.getNumber())) {
                    continue;
                }
                int walkToStart = starts.walkDurations().get(i);
                int cycle = cycleMatrix[i][j];
                int walkFromEnd =
                        walkFromEndStations.length > j ? walkFromEndStations[j][0] : GoogleMapsClient.UNREACHABLE_DURATION;
                if (cycle == GoogleMapsClient.UNREACHABLE_DURATION
                        || walkFromEnd == GoogleMapsClient.UNREACHABLE_DURATION) {
                    continue;
                }
                int total = walkToStart + cycle + walkFromEnd;
                if (bestTotal == null || total < bestTotal) {
                    bestTotal = total;
                    bestStart = i;
                    bestEnd = j;
                    bestCycle = cycle;
                    bestWalkToStart = walkToStart;
                    bestWalkFromEnd = walkFromEnd;
                }
            }
        }
        if (bestTotal == null) {
            return null;
        }
        return new BestRoute(bestStart, bestEnd, bestWalkToStart, bestCycle, bestWalkFromEnd, bestTotal);
    }

    private LatLon stationCoord(StationAvailability candidate) {
        Station station = candidate.station();
        return new LatLon(station.getLatitude(), station.getLongitude());
    }

    private static GeoCoordinateVO toCoord(Station station) {
        return new GeoCoordinateVO(station.getLatitude(), station.getLongitude());
    }

    private static BusinessException noRoute() {
        return new BusinessException(ApiCodes.NO_AVAILABLE_ROUTE, "no available route", 404);
    }

    private record StationAvailability(Station station, Availability availability) {}

    private record RankedCandidates(List<StationAvailability> candidates, List<Integer> walkDurations) {}

    private record IndexedDuration(int index, int duration) {}

    private record BestRoute(
            int startIndex,
            int endIndex,
            int walkToStart,
            int cycleTime,
            int walkFromEnd,
            int totalDuration) {}
}
