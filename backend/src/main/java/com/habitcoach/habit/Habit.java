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

    /** Anonymous per-installation owner (see mobile's deviceId.ts,
     * profile.UserProfile's javadoc for the same pattern) — every
     * repository/service/controller method that touches a Habit is scoped
     * by this, which is what stops a fresh install/different device from
     * ever listing or opening another device's challenges. @ColumnDefault
     * is required (not cosmetic): adding a NOT NULL column via
     * ddl-auto=update against an existing, non-empty habits table fails
     * without a DB-level default to backfill existing rows with; any
     * pre-existing row is attributed to this fixed sentinel device id
     * rather than being deleted or silently shared. */
    @Column(name = "device_id", nullable = false)
    @ColumnDefault("'legacy-device'")
    private String deviceId;

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

    protected Habit() {
        // JPA
    }

    public Habit(String deviceId, String name, String emoji, String timeOfDay, Integer durationMinutes, Integer totalDays) {
        this.deviceId = deviceId;
        this.name = name;
        this.emoji = emoji;
        this.timeOfDay = timeOfDay;
        this.durationMinutes = durationMinutes;
        this.totalDays = totalDays;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
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
}
