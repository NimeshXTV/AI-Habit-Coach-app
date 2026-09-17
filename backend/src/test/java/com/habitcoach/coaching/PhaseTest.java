package com.habitcoach.coaching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expected phases below were captured by running the untouched reference's
 * _phase() directly (via _probe scripts against ~/habit-coach/backend),
 * including the important detail that thresholds are literal fractions of
 * a 21-day journey (3/21, 7/21, 14/21, 18/21) applied to day/total — NOT
 * rescaled when total_days isn't 21.
 */
class PhaseTest {

    @Test
    void boundariesOnA21DayJourney() {
        assertThat(Phase.of(1, 21)).isEqualTo(Phase.GETTING_STARTED);
        assertThat(Phase.of(3, 21)).isEqualTo(Phase.GETTING_STARTED);
        assertThat(Phase.of(4, 21)).isEqualTo(Phase.BUILDING_CONSISTENCY);
        assertThat(Phase.of(7, 21)).isEqualTo(Phase.BUILDING_CONSISTENCY);
        assertThat(Phase.of(8, 21)).isEqualTo(Phase.HANDLING_RESISTANCE);
        assertThat(Phase.of(14, 21)).isEqualTo(Phase.HANDLING_RESISTANCE);
        assertThat(Phase.of(15, 21)).isEqualTo(Phase.REINFORCEMENT);
        assertThat(Phase.of(18, 21)).isEqualTo(Phase.REINFORCEMENT);
        assertThat(Phase.of(19, 21)).isEqualTo(Phase.COMPLETION);
        assertThat(Phase.of(21, 21)).isEqualTo(Phase.COMPLETION);
    }

    @Test
    void thresholdsAreFixedFractionsNotRescaledForShorterJourneys() {
        // day 3 of a 14-day habit: ratio 3/14 ≈ 0.214, which is ABOVE the
        // fixed 3/21 ≈ 0.143 threshold, so it's building_consistency, not
        // getting_started — confirmed against the reference directly.
        assertThat(Phase.of(3, 14)).isEqualTo(Phase.BUILDING_CONSISTENCY);
        assertThat(Phase.of(2, 14)).isEqualTo(Phase.GETTING_STARTED);
    }

    @Test
    void day16Of21IsReinforcementPhaseEvenThoughNotAMilestoneDay() {
        // Not one of the (7, 14, 21) milestone days, but still lands in the
        // reinforcement phase purely by ratio — this is what lets
        // StrategySelector return REINFORCEMENT for non-milestone days too.
        assertThat(Phase.of(16, 21)).isEqualTo(Phase.REINFORCEMENT);
    }
}
