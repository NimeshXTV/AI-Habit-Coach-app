package com.habitcoach.goal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expected values below are a golden master captured by running the
 * untouched reference implementation directly:
 *   PYTHONPATH=~/habit-coach/backend python3 -c "from goal_parser import parse_goal; ..."
 * for each input string. This is what "port the behavior, don't invent it"
 * means in practice — these are not hand-derived, they're the reference's
 * own output.
 */
class GoalParserTest {

    private final GoalParser parser = new GoalParser();

    @Test
    void explicitEveningTimeAndDayCount() {
        ParsedGoal parsed = parser.parse("I want to go to the gym every day at 6 PM for the next 21 days");

        assertThat(parsed.name()).isEqualTo("Go to the gym");
        assertThat(parsed.emoji()).isEqualTo("🏋️");
        assertThat(parsed.timeOfDay()).isEqualTo("18:00");
        assertThat(parsed.durationMinutes()).isEqualTo(30);
        assertThat(parsed.totalDays()).isEqualTo(21);
        assertThat(parsed.timeSpecified()).isTrue();
    }

    @Test
    void hourDurationAndLowercasePm() {
        ParsedGoal parsed = parser.parse("study Java for one hour every evening at 7pm for 21 days");

        assertThat(parsed.name()).isEqualTo("Study Java");
        assertThat(parsed.emoji()).isEqualTo("💻");
        assertThat(parsed.timeOfDay()).isEqualTo("19:00");
        assertThat(parsed.durationMinutes()).isEqualTo(60);
        assertThat(parsed.totalDays()).isEqualTo(21);
        assertThat(parsed.timeSpecified()).isTrue();
    }

    @Test
    void dayPartWordFallsBackToHeuristicTime() {
        ParsedGoal parsed = parser.parse("I want to build a habit of meditating for 10 minutes every morning");

        assertThat(parsed.name()).isEqualTo("Meditating");
        assertThat(parsed.emoji()).isEqualTo("🧘");
        assertThat(parsed.timeOfDay()).isEqualTo("07:00");
        assertThat(parsed.durationMinutes()).isEqualTo(10);
        assertThat(parsed.totalDays()).isEqualTo(21);
        assertThat(parsed.timeSpecified()).isFalse();
    }

    @Test
    void noExplicitTimeOrDurationUsesDefaults() {
        ParsedGoal parsed = parser.parse("drink water every day");

        assertThat(parsed.name()).isEqualTo("Drink water");
        assertThat(parsed.emoji()).isEqualTo("💧");
        assertThat(parsed.timeOfDay()).isEqualTo("18:00");
        assertThat(parsed.durationMinutes()).isEqualTo(30);
        assertThat(parsed.totalDays()).isEqualTo(21);
        assertThat(parsed.timeSpecified()).isFalse();
    }

    @Test
    void explicitTotalDaysOverridesDefault() {
        ParsedGoal parsed = parser.parse("read for 20 minutes every night for 14 days");

        assertThat(parsed.name()).isEqualTo("Read");
        assertThat(parsed.emoji()).isEqualTo("📚");
        assertThat(parsed.timeOfDay()).isEqualTo("21:00");
        assertThat(parsed.durationMinutes()).isEqualTo(20);
        assertThat(parsed.totalDays()).isEqualTo(14);
        assertThat(parsed.timeSpecified()).isFalse();
    }

    @Test
    void leadingPhrasingIAmWouldLikeIsStripped() {
        ParsedGoal parsed = parser.parse("I'd like to journal every evening");

        assertThat(parsed.name()).isEqualTo("Journal");
        assertThat(parsed.emoji()).isEqualTo("✍️");
        assertThat(parsed.timeOfDay()).isEqualTo("19:00");
        assertThat(parsed.timeSpecified()).isFalse();
    }

    @Test
    void colonTimeWithoutMeridiemViaAtKeyword() {
        ParsedGoal parsed = parser.parse("run 5k every morning at 6:30");

        assertThat(parsed.name()).isEqualTo("Run 5k");
        assertThat(parsed.timeOfDay()).isEqualTo("06:30");
        assertThat(parsed.timeSpecified()).isTrue();
    }

    @Test
    void emptyTextFallsBackToMyHabitAndAllDefaults() {
        ParsedGoal parsed = parser.parse("");

        assertThat(parsed.name()).isEqualTo("My Habit");
        assertThat(parsed.emoji()).isEqualTo("🎯");
        assertThat(parsed.timeOfDay()).isEqualTo("18:00");
        assertThat(parsed.durationMinutes()).isEqualTo(30);
        assertThat(parsed.totalDays()).isEqualTo(21);
        assertThat(parsed.timeSpecified()).isFalse();
    }

    @Test
    void letsStartHabitOfPhrasingAndExplicitDayCount() {
        ParsedGoal parsed = parser.parse("Let's start a habit of writing at 9pm for 30 days");

        assertThat(parsed.name()).isEqualTo("Writing");
        assertThat(parsed.emoji()).isEqualTo("✍️");
        assertThat(parsed.timeOfDay()).isEqualTo("21:00");
        assertThat(parsed.totalDays()).isEqualTo(30);
        assertThat(parsed.timeSpecified()).isTrue();
    }

    @Test
    void wordNumberHalfAnHourDuration() {
        ParsedGoal parsed = parser.parse("workout for half an hour every afternoon");

        assertThat(parsed.name()).isEqualTo("Workout for half an hour");
        assertThat(parsed.timeOfDay()).isEqualTo("15:00");
        assertThat(parsed.durationMinutes()).isEqualTo(30);
        assertThat(parsed.timeSpecified()).isFalse();
    }
}
