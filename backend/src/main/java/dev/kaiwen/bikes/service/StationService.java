package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.StationMapper;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationService {

    private final StationRepository stationRepository;
    private final AvailabilityRepository availabilityRepository;
    private final StationMapper stationMapper;

    public List<StationVO> listStations() {
        return stationMapper.toVOList(stationRepository.findAllByOrderByNumberAsc());
    }

    public List<AvailabilityVO> getRecentAvailability(int number) {
        requireStation(number);
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        return stationMapper.toAvailabilityVOList(availabilityRepository.findRecent(number, since));
    }

    public List<AvailabilityVO> getLatestStatusForAllStations() {
        return stationMapper.toAvailabilityVOList(availabilityRepository.findLatestPerStation());
    }

    private void requireStation(int number) {
        if (!stationRepository.existsById(number)) {
            throw new BusinessException(ApiCodes.STATION_NOT_FOUND, "station not found", 404);
        }
    }
}
