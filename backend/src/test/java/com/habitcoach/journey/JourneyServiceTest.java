package com.habitcoach.journey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JourneyServiceTest {

    @Autowired
    private HabitDayRepository habitDayRepository;

    private JourneyService journeyService() {
        return new JourneyService(habitDayRepository);
    }

    @Test
    void initializesExactlyTotalDaysPendingRowsInOrder() {
        List<HabitDay> days = journeyService().initializeDays(42L, 21);

        assertThat(days).hasSize(21);
        assertThat(days).allMatch(d -> d.getHabitId().equals(42L));
        assertThat(days).allMatch(d -> d.getStatus() == DayStatus.PENDING);
        assertThat(days).extracting(HabitDay::getDayNumber)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21);
    }

    @Test
    void respectsNonDefaultTotalDays() {
        List<HabitDay> days = journeyService().initializeDays(7L, 14);

        assertThat(days).hasSize(14);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(7L)).hasSize(14);
    }

    @Test
    void daysAreScopedToTheirOwnHabit() {
        journeyService().initializeDays(1L, 5);
        journeyService().initializeDays(2L, 3);

        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(1L)).hasSize(5);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(2L)).hasSize(3);
    }

    @Test
    void currentDayNumberIsOneOnAFreshHabit() {
        List<HabitDay> days = journeyService().initializeDays(10L, 21);
        assertThat(JourneyService.currentDayNumber(days)).isEqualTo(1);
    }

    @Test
    void currentDayNumberIsFirstPendingDay() {
        List<HabitDay> days = journeyService().initializeDays(11L, 5);
        days.get(0).setStatus(DayStatus.DONE);
        days.get(1).setStatus(DayStatus.MISSED);

        assertThat(JourneyService.currentDayNumber(days)).isEqualTo(3);
    }

    @Test
    void currentDayNumberIsLastDayWhenAllResolved() {
        List<HabitDay> days = journeyService().initializeDays(12L, 3);
        days.forEach(d -> d.setStatus(DayStatus.DONE));

        assertThat(JourneyService.currentDayNumber(days)).isEqualTo(3);
    }

    @Test
    void currentDayNumberIsOneForAnEmptyDayList() {
        assertThat(JourneyService.currentDayNumber(List.of())).isEqualTo(1);
    }

    @Test
    void recordInterventionStoresTextAndStrategyOnTheRightDay() {
        journeyService().initializeDays(13L, 3);

        journeyService().recordIntervention(13L, 2, "You're doing great", "encouragement");

        HabitDay day2 = habitDayRepository.findByHabitIdAndDayNumber(13L, 2).orElseThrow();
        assertThat(day2.getInterventionText()).isEqualTo("You're doing great");
        assertThat(day2.getInterventionStrategy()).isEqualTo("encouragement");
        // other days untouched
        HabitDay day1 = habitDayRepository.findByHabitIdAndDayNumber(13L, 1).orElseThrow();
        assertThat(day1.getInterventionText()).isNull();
    }
}
