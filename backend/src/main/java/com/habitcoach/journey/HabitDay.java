package com.habitcoach.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Mirrors the reference `habit_days` table (legacy-reference/backend/db.py).
 * habit_id is stored as a plain FK column rather than a JPA @ManyToOne for
 * this scaffold stage, deferring the relationship-mapping decision (and
 * cascade-delete wiring called out in the architecture review) to the
 * feature-migration phase.
 */
@Entity
@Table(
        name = "habit_days",
        uniqueConstraints = @UniqueConstraint(columnNames = {"habit_id", "day_number"}),
        indexes = @Index(columnList = "habit_id")
)
public class HabitDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "habit_id", nullable = false)
    private Long habitId;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DayStatus status = DayStatus.PENDING;

    private String action;

    @Column(name = "feedback_reason")
    private String feedbackReason;

    @Column(name = "feedback_note")
    private String feedbackNote;

    @Column(name = "intervention_text")
    private String interventionText;

    @Column(name = "intervention_strategy")
    private String interventionStrategy;

    @Column(name = "snooze_count", nullable = false)
    private Integer snoozeCount = 0;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected HabitDay() {
        // JPA
    }

    public HabitDay(Long habitId, Integer dayNumber) {
        this.habitId = habitId;
        this.dayNumber = dayNumber;
    }

    public Long getId() {
        return id;
    }

    public Long getHabitId() {
        return habitId;
    }

    public Integer getDayNumber() {
        return dayNumber;
    }

    public DayStatus getStatus() {
        return status;
    }

    public void setStatus(DayStatus status) {
        this.status = status;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFeedbackReason() {
        return feedbackReason;
    }

    public void setFeedbackReason(String feedbackReason) {
        this.feedbackReason = feedbackReason;
    }

    public String getFeedbackNote() {
        return feedbackNote;
    }

    public void setFeedbackNote(String feedbackNote) {
        this.feedbackNote = feedbackNote;
    }

    public String getInterventionText() {
        return interventionText;
    }

    public void setInterventionText(String interventionText) {
        this.interventionText = interventionText;
    }

    public String getInterventionStrategy() {
        return interventionStrategy;
    }

    public void setInterventionStrategy(String interventionStrategy) {
        this.interventionStrategy = interventionStrategy;
    }

    public Integer getSnoozeCount() {
        return snoozeCount;
    }

    public void setSnoozeCount(Integer snoozeCount) {
        this.snoozeCount = snoozeCount;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
