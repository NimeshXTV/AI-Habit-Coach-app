package com.habitcoach.habit;

import com.habitcoach.goal.GoalParser;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.HabitDayRepository;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.web.InvalidRequestException;
import com.habitcoach.web.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HabitService/JourneyService/GoalParser beans against a
 * real (in-memory, for tests) database — verifies the create -> persist ->
 * pre-create-21-days pipeline end to end, matching legacy-reference/backend/
 * db.py's create_habit() behavior.
 */
@DataJpaTest
@Import({HabitService.class, JourneyService.class, GoalParser.class})
class HabitServiceTest {

    @Autowired
    private HabitService habitService;

    @Autowired
    private HabitDayRepository habitDayRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createFromTextParsesAndPersistsWithTwentyOneDays() {
        Habit habit = habitService.createFromText("I want to go to the gym every day at 6 PM for the next 21 days");

        assertThat(habit.getId()).isNotNull();
        assertThat(habit.getName()).isEqualTo("Go to the gym");
        assertThat(habit.getTimeOfDay()).isEqualTo("18:00");
        assertThat(habit.getTotalDays()).isEqualTo(21);
        assertThat(habit.getStatus()).isEqualTo(HabitStatus.ACTIVE);

        List<HabitDay> days = habitDayRepository.findByHabitIdOrderByDayNumber(habit.getId());
        assertThat(days).hasSize(21);
        assertThat(days).allMatch(d -> d.getStatus().name().equals("PENDING"));
    }

    @Test
    void createFromTextRespectsNonDefaultTotalDaysFromText() {
        Habit habit = habitService.createFromText("read for 20 minutes every night for 14 days");

        assertThat(habit.getTotalDays()).isEqualTo(14);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(habit.getId())).hasSize(14);
    }

    @Test
    void createConfirmedOverridesParsedTimeWithUserChoice() {
        // "drink water every day" parses to no explicit time (defaults to 18:00);
        // confirm should use the user-picked time instead, exactly like the
        // reference's confirm_habit() overriding goal_parser's guess.
        Habit habit = habitService.createConfirmed("drink water every day", "09:15");

        assertThat(habit.getName()).isEqualTo("Drink water");
        assertThat(habit.getTimeOfDay()).isEqualTo("09:15");
    }

    @Test
    void createConfirmedRejectsMalformedTimeOfDay() {
        assertThatThrownBy(() -> habitService.createConfirmed("drink water every day", "9:15am"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("time_of_day must be 'HH:MM' 24h");
    }

    @Test
    void createConfirmedRejectsOutOfRangeTimeOfDay() {
        assertThatThrownBy(() -> habitService.createConfirmed("drink water every day", "25:00"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void multipleHabitsCoexistWithDistinctIdsAndOwnDays() {
        Habit gym = habitService.createFromText("go to the gym every day at 6pm");
        Habit read = habitService.createFromText("read every night for 14 days");

        assertThat(gym.getId()).isNotEqualTo(read.getId());

        List<Habit> all = habitService.listHabits();
        assertThat(all).extracting(Habit::getId).contains(gym.getId(), read.getId());

        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(gym.getId())).hasSize(21);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(read.getId())).hasSize(14);
    }

    @Test
    void listHabitsReturnsMostRecentFirst() {
        Habit first = habitService.createFromText("gym every day");
        Habit second = habitService.createFromText("read every day");

        List<Habit> all = habitService.listHabits();

        assertThat(all.get(0).getId()).isEqualTo(second.getId());
        assertThat(all.get(1).getId()).isEqualTo(first.getId());
    }

    @Test
    void getHabitReturnsPersistedHabit() {
        Habit created = habitService.createFromText("gym every day at 6pm");

        Habit fetched = habitService.getHabit(created.getId());

        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getName()).isEqualTo(created.getName());
    }

    @Test
    void getHabitThrowsNotFoundForUnknownId() {
        assertThatThrownBy(() -> habitService.getHabit(999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("habit not found");
    }

    @Test
    void neverAssumesHabitIdOne() {
        // Create a throwaway habit first so the one under test is NOT id 1,
        // then verify lookup still works by its real (non-1) id.
        habitService.createFromText("throwaway habit");
        Habit second = habitService.createFromText("gym every day at 6pm");

        assertThat(second.getId()).isNotEqualTo(1L);
        Habit fetched = habitService.getHabit(second.getId());
        assertThat(fetched.getId()).isEqualTo(second.getId());
        assertThat(fetched.getName()).isEqualTo(second.getName());
    }

    // ---- scheduling ----

    @Test
    void confirmedInitialTimeIsWhatTheUserPicked() {
        Habit habit = habitService.createConfirmed("meditate every day", "06:45");
        assertThat(habit.getTimeOfDay()).isEqualTo("06:45");
    }

    @Test
    void updateScheduleChangesOnlyTimeOfDay() {
        Habit habit = habitService.createFromText("gym every day at 6pm");

        Habit updated = habitService.updateSchedule(habit.getId(), "19:30");

        assertThat(updated.getTimeOfDay()).isEqualTo("19:30");
        assertThat(updated.getName()).isEqualTo(habit.getName());
        assertThat(updated.getDurationMinutes()).isEqualTo(habit.getDurationMinutes());
        assertThat(updated.getTotalDays()).isEqualTo(habit.getTotalDays());
    }

    @Test
    void updateScheduleRejectsMalformedTime() {
        Habit habit = habitService.createFromText("gym every day at 6pm");

        assertThatThrownBy(() -> habitService.updateSchedule(habit.getId(), "7:30pm"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("time_of_day must be 'HH:MM' 24h");

        // rejected change must not have taken effect
        assertThat(habitService.getHabit(habit.getId()).getTimeOfDay()).isEqualTo("18:00");
    }

    @Test
    void updateScheduleRejectsOutOfRangeTime() {
        Habit habit = habitService.createFromText("gym every day at 6pm");

        assertThatThrownBy(() -> habitService.updateSchedule(habit.getId(), "24:00"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> habitService.updateSchedule(habit.getId(), "12:60"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void updateScheduleThrowsNotFoundForUnknownHabit() {
        assertThatThrownBy(() -> habitService.updateSchedule(999_999L, "09:00"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("habit not found");
    }

    @Test
    void updateScheduleOnOneHabitDoesNotAffectAnother() {
        Habit gym = habitService.createFromText("gym every day at 6pm");
        Habit read = habitService.createFromText("read every night for 14 days");

        habitService.updateSchedule(gym.getId(), "20:00");

        assertThat(habitService.getHabit(gym.getId()).getTimeOfDay()).isEqualTo("20:00");
        assertThat(habitService.getHabit(read.getId()).getTimeOfDay()).isEqualTo("21:00");
    }

    @Test
    void scheduleChangePersistsAcrossAFreshEntityManagerReload() {
        // Forces a genuine round trip to the database (clears Hibernate's
        // first-level cache) rather than trusting an in-memory managed
        // entity reference — this is what "persists across reload/restart"
        // actually means for a JPA-backed service.
        Habit habit = habitService.createFromText("gym every day at 6pm");
        habitService.updateSchedule(habit.getId(), "20:15");

        entityManager.flush();
        entityManager.clear();

        Habit reloaded = habitService.getHabit(habit.getId());
        assertThat(reloaded.getTimeOfDay()).isEqualTo("20:15");
    }

    // ---- continue / stop / delete ----

    @Test
    void continueHabitCreatesAFreshHabitReusingNameAndTime() {
        Habit original = habitService.createFromText("gym every day at 6pm");

        Habit continued = habitService.continueHabit(original.getId(), null);

        assertThat(continued.getId()).isNotEqualTo(original.getId());
        assertThat(continued.getName()).isEqualTo("Gym");
        assertThat(continued.getTimeOfDay()).isEqualTo("18:00");
        assertThat(continued.getTotalDays()).isEqualTo(21);
        assertThat(continued.getStatus()).isEqualTo(HabitStatus.ACTIVE);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(continued.getId())).hasSize(21);
        // original habit is untouched
        assertThat(habitService.getHabit(original.getId()).getId()).isEqualTo(original.getId());
    }

    @Test
    void continueHabitResetsDurationToDefaultMatchingReferenceQuirk() {
        // Faithful port: the reference's continue text never mentions
        // duration, so goal_parser falls back to its 30-minute default
        // regardless of the finished habit's actual duration.
        Habit original = habitService.createConfirmed("study Java for one hour every evening", "19:00");
        assertThat(original.getDurationMinutes()).isEqualTo(60);

        Habit continued = habitService.continueHabit(original.getId(), null);

        assertThat(continued.getDurationMinutes()).isEqualTo(30);
    }

    @Test
    void continueHabitThrowsNotFoundForUnknownHabit() {
        assertThatThrownBy(() -> habitService.continueHabit(999_999L, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void stopHabitSetsStatusToStopped() {
        Habit habit = habitService.createFromText("gym every day at 6pm");

        habitService.stopHabit(habit.getId());

        assertThat(habitService.getHabit(habit.getId()).getStatus()).isEqualTo(HabitStatus.STOPPED);
    }

    @Test
    void stopHabitOnUnknownIdDoesNotThrow() {
        // Matches the reference's db.update_habit_status(): silently
        // affects zero rows rather than 404ing.
        assertThatCode(() -> habitService.stopHabit(999_999L)).doesNotThrowAnyException();
    }

    @Test
    void deleteHabitRemovesHabitAndAllItsDays() {
        Habit habit = habitService.createFromText("gym every day at 6pm");
        Long id = habit.getId();
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(id)).hasSize(21);

        habitService.deleteHabit(id);

        assertThatThrownBy(() -> habitService.getHabit(id)).isInstanceOf(NotFoundException.class);
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(id)).isEmpty();
    }

    @Test
    void deleteHabitThrowsNotFoundForUnknownHabit() {
        assertThatThrownBy(() -> habitService.deleteHabit(999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("habit not found");
    }

    @Test
    void deletingOneHabitDoesNotAffectAnother() {
        Habit gym = habitService.createFromText("gym every day at 6pm");
        Habit read = habitService.createFromText("read every night for 14 days");

        habitService.deleteHabit(gym.getId());

        assertThatThrownBy(() -> habitService.getHabit(gym.getId())).isInstanceOf(NotFoundException.class);
        assertThat(habitService.getHabit(read.getId()).getId()).isEqualTo(read.getId());
        assertThat(habitDayRepository.findByHabitIdOrderByDayNumber(read.getId())).hasSize(14);
    }
}
