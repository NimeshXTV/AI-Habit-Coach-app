package com.habitcoach.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitDayRepository extends JpaRepository<HabitDay, Long> {

    List<HabitDay> findByHabitIdOrderByDayNumber(Long habitId);

    Optional<HabitDay> findByHabitIdAndDayNumber(Long habitId, Integer dayNumber);

    long countByHabitIdAndStatus(Long habitId, DayStatus status);

    void deleteByHabitId(Long habitId);
}
