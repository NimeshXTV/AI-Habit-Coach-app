package com.habitcoach.habit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JPA/H2 wiring works end to end: an entity can be saved and
 * read back through the repository, on a real (in-memory, for tests)
 * database — not just that the classes compile.
 */
@DataJpaTest
class HabitRepositoryTest {

    @Autowired
    private HabitRepository habitRepository;

    @Test
    void savesAndListsHabitsMostRecentFirst() {
        habitRepository.save(new Habit("Gym", "🏋️", "18:00", 30, 21));
        habitRepository.save(new Habit("Read", "📚", "21:00", 20, 21));

        List<Habit> habits = habitRepository.findAllByOrderByIdDesc();

        assertThat(habits).hasSize(2);
        assertThat(habits.get(0).getName()).isEqualTo("Read");
        assertThat(habits.get(0).getStatus()).isEqualTo(HabitStatus.ACTIVE);
        assertThat(habits.get(1).getName()).isEqualTo("Gym");
    }
}
