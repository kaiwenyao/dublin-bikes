package dev.kaiwen.bikes.mapper;

import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StationMapper {

    StationVO toVO(Station station);

    List<StationVO> toVOList(List<Station> stations);

    AvailabilityVO toVO(Availability availability);

    List<AvailabilityVO> toAvailabilityVOList(List<Availability> availability);

    default String map(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
