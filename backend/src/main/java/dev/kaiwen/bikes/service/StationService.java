package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.StationMapper;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationService {

    /**
     * Look-back window for {@code GET /api/stations/status}.
     *
     * <p>The scraper writes every station every 5 minutes, so 24 h is ~288 rows/station (≈33k rows)
     * for the window function to rank — versus ~1M rows for the whole retained history. A station
     * with no row in this window drops out of the response and the frontend already renders it with
     * the "no status" grey marker; that only happens when the whole scraper is down for a day.
     */
    static final int LATEST_WINDOW_HOURS = 24;

    private final StationRepository stationRepository;
    private final AvailabilityRepository availabilityRepository;
    private final StationMapper stationMapper;

    public List<StationVO> listStations() {
        return stationMapper.toVOList(stationRepository.findAllByOrderByNumberAsc());
    }

    public List<AvailabilityVO> getRecentAvailability(int number) {
        requireStation(number);
        // availability.timestamp / requested_at are naive UTC (scraper/time_utils); never use JVM default zone
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        return stationMapper.toAvailabilityVOList(availabilityRepository.findRecent(number, since));
    }

    public List<AvailabilityVO> getLatestStatusForAllStations() {
        // availability.timestamp is naive UTC (scraper/time_utils); never use JVM default zone
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(LATEST_WINDOW_HOURS);
        return stationMapper.toAvailabilityVOList(
                availabilityRepository.findLatestPerStationSince(since));
    }

    private void requireStation(int number) {
        if (!stationRepository.existsById(number)) {
            throw new BusinessException(ApiCodes.STATION_NOT_FOUND, "station not found", 404);
        }
    }
}
