package com.habitcoach.habit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Mirrors the reference FastAPI project's `habits` table (see
 * legacy-reference/backend/db.py) field-for-field. time_of_day is kept as a
 * plain 'HH:mm' string, not java.time.LocalTime, so it round-trips through
 * JSON/mobile exactly as before with no timezone conversion involved.
 */
@Entity
@Table(name = "habits")
public class Habit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String emoji = "🎯";

    @Column(name = "time_of_day", nullable = false, length = 5)
    private String timeOfDay;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 30;

    @Column(name = "total_days", nullable = false)
    private Integer totalDays = 21;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HabitStatus status = HabitStatus.ACTIVE;

    /** Habit-tree gamification (see TreeHealth) — one tree per habit, not a
     * separate table: kept directly on Habit per the product requirement to
     * avoid unnecessary architecture for what is just one extra int.
     * @ColumnDefault is required, not cosmetic: without a DB-level default,
     * Hibernate's `ddl-auto=update` ALTER TABLE ADD COLUMN ... NOT NULL
     * fails outright against an existing, non-empty habits table (verified
     * against a copy of the real dev DB) — H2 has no value to backfill
     * existing rows with otherwise. Kept in sync with TreeHealth.SEED by
     * TreeHealthTest.seedMatchesHabitsColumnDefault(). */
    @Column(name = "tree_health", nullable = false)
    @ColumnDefault("50")
    private Integer treeHealth = TreeHealth.SEED;

    protected Habit() {
        // JPA
    }

    public Habit(String name, String emoji, String timeOfDay, Integer durationMinutes, Integer totalDays) {
        this.name = name;
        this.emoji = emoji;
        this.timeOfDay = timeOfDay;
        this.durationMinutes = durationMinutes;
        this.totalDays = totalDays;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public String getTimeOfDay() {
        return timeOfDay;
    }

    public void setTimeOfDay(String timeOfDay) {
        this.timeOfDay = timeOfDay;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getTotalDays() {
        return totalDays;
    }

    public void setTotalDays(Integer totalDays) {
        this.totalDays = totalDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public HabitStatus getStatus() {
        return status;
    }

    public void setStatus(HabitStatus status) {
        this.status = status;
    }

    public Integer getTreeHealth() {
        return treeHealth;
    }

    public void setTreeHealth(Integer treeHealth) {
        this.treeHealth = treeHealth;
    }

    /** Computed, not persisted (no backing field -> JPA's field-based access
     * ignores it entirely) — always in sync with treeHealth by construction.
     * Serializes as "tree_stage" alongside "tree_health" on every response
     * that already embeds a Habit (list, detail, current, action, ...). */
    public TreeStage getTreeStage() {
        return TreeStage.of(treeHealth);
    }
}
