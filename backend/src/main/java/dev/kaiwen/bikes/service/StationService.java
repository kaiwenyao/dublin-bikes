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
     * Look-back window for {@code GET /api/stations/status}, measured in <em>scrape</em> time
     * ({@code availability.requested_at}), not in upstream {@code last_update} time.
     *
     * <p>The scraper writes every station every 5 minutes, so 24 h is ~288 rows/station (≈33k rows)
     * for the window function to rank — versus ~1M rows for the whole retained history. A station
     * drops out only when the scraper has not written a row for it in this window (the frontend
     * already renders that as the "no status" grey marker); a station whose JCDecaux
     * {@code last_update} is frozen — closed for maintenance, broken dock — keeps its status as
     * long as we keep collecting it.
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
        // Bound by scrape time: requested_at is when the scraper collected the row, whereas
        // timestamp mirrors JCDecaux's last_update, which can stay frozen for days on a station
        // that is still being collected. Both are naive UTC (scraper/time_utils).
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(LATEST_WINDOW_HOURS);
        return stationMapper.toAvailabilityVOList(
                availabilityRepository.findLatestPerStationScrapedSince(since));
    }

    private void requireStation(int number) {
        if (!stationRepository.existsById(number)) {
            throw new BusinessException(ApiCodes.STATION_NOT_FOUND, "station not found", 404);
        }
    }
}
