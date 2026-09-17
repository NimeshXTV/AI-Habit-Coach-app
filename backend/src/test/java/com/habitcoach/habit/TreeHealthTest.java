package com.habitcoach.habit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TreeHealthTest {

    @Test
    void doneAddsTenPoints() {
        assertThat(TreeHealth.apply(50, "done")).isEqualTo(60);
    }

    @Test
    void missedSubtractsTenPoints() {
        assertThat(TreeHealth.apply(50, "missed")).isEqualTo(40);
    }

    @Test
    void snoozedLeavesHealthUnchanged() {
        assertThat(TreeHealth.apply(50, "snoozed")).isEqualTo(50);
    }

    @Test
    void clampsAtZeroNeverGoesNegative() {
        assertThat(TreeHealth.apply(5, "missed")).isEqualTo(0);
        assertThat(TreeHealth.apply(0, "missed")).isEqualTo(0);
    }

    @Test
    void clampsAtOneHundredNeverExceedsIt() {
        assertThat(TreeHealth.apply(95, "done")).isEqualTo(100);
        assertThat(TreeHealth.apply(100, "done")).isEqualTo(100);
    }

    @Test
    void seedIsInTheMiddleOfTheRange() {
        assertThat(TreeHealth.SEED).isBetween(TreeHealth.MIN, TreeHealth.MAX);
        assertThat(TreeStage.of(TreeHealth.SEED)).isEqualTo(TreeStage.GROWING);
    }

    @Test
    void seedMatchesHabitsColumnDefault() {
        // Habit.treeHealth carries @ColumnDefault("50") so Hibernate's
        // ddl-auto=update can backfill the new column on an existing,
        // non-empty habits table (see Habit's javadoc) — that literal has
        // to be kept in sync with TreeHealth.SEED by hand, since annotation
        // values must be compile-time constants. This test is the tripwire:
        // if SEED ever changes, this fails as a reminder to update the
        // annotation too.
        assertThat(TreeHealth.SEED).isEqualTo(50);
    }
}
