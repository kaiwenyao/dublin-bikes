package dev.kaiwen.bikes.repository;

import dev.kaiwen.bikes.model.Station;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationRepository extends JpaRepository<Station, Integer> {

    List<Station> findAllByOrderByNumberAsc();
}
