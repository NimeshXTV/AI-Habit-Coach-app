package com.habitcoach.habit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The finders declared here are deviceId-scoped on purpose — see Habit's
 * javadoc. JpaRepository still technically exposes unscoped
 * findById()/findAll() by inheritance, but HabitService must never call
 * those for anything user-facing: findAllByDeviceIdOrderByIdDesc /
 * findByIdAndDeviceId are the only lookups any controller-reachable code
 * path should use. An unscoped lookup is exactly the mistake that let one
 * device see another device's challenges before this class existed.
 */
public interface HabitRepository extends JpaRepository<Habit, Long> {

    List<Habit> findAllByDeviceIdOrderByIdDesc(String deviceId);

    Optional<Habit> findByIdAndDeviceId(Long id, String deviceId);
}
