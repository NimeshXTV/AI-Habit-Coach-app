package com.habitcoach.habit;

import com.habitcoach.journey.HabitDay;

import java.util.List;

/** Mirrors the reference's GET /api/habits/{id} response shape:
 * {"habit": {...}, "days": [...]}. */
public record HabitDetailResponse(Habit habit, List<HabitDay> days) {
}
