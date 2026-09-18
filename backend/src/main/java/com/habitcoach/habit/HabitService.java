package com.habitcoach.habit;

import com.habitcoach.goal.GoalParser;
import com.habitcoach.goal.ParsedGoal;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.web.InvalidRequestException;
import com.habitcoach.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Habit lifecycle. Ported from legacy-reference/backend/main.py's
 * create_habit/confirm_habit routes plus db.py's create_habit() (which
 * pre-creates all HabitDay rows — see JourneyService).
 */
@Service
public class HabitService {

    /** Same format the reference enforces in POST /api/habits/confirm. */
    private static final Pattern TIME_OF_DAY = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");

    private final HabitRepository habitRepository;
    private final JourneyService journeyService;
    private final GoalParser goalParser;

    public HabitService(HabitRepository habitRepository, JourneyService journeyService, GoalParser goalParser) {
        this.habitRepository = habitRepository;
        this.journeyService = journeyService;
        this.goalParser = goalParser;
    }

    /** POST /api/habits — direct create, used when the goal text already
     * named an explicit time. */
    @Transactional
    public Habit createFromText(String deviceId, String text) {
        ParsedGoal parsed = goalParser.parse(text);
        return persist(deviceId, parsed.name(), parsed.emoji(), parsed.timeOfDay(), parsed.durationMinutes(), parsed.totalDays());
    }

    /** POST /api/habits/confirm — create with a user-picked time, overriding
     * whatever goal_parser guessed (used after a time_specified: false parse). */
    @Transactional
    public Habit createConfirmed(String deviceId, String text, String timeOfDay) {
        if (!TIME_OF_DAY.matcher(timeOfDay).matches()) {
            throw new InvalidRequestException("time_of_day must be 'HH:MM' 24h");
        }
        ParsedGoal parsed = goalParser.parse(text);
        return persist(deviceId, parsed.name(), parsed.emoji(), timeOfDay, parsed.durationMinutes(), parsed.totalDays());
    }

    private Habit persist(String deviceId, String name, String emoji, String timeOfDay, int durationMinutes, int totalDays) {
        Habit habit = habitRepository.save(new Habit(deviceId, name, emoji, timeOfDay, durationMinutes, totalDays));
        journeyService.initializeDays(habit.getId(), totalDays);
        return habit;
    }

    public List<Habit> listHabits(String deviceId) {
        return habitRepository.findAllByDeviceIdOrderByIdDesc(deviceId);
    }

    public Habit getHabit(String deviceId, Long id) {
        return habitRepository.findByIdAndDeviceId(id, deviceId)
                .orElseThrow(() -> new NotFoundException("habit not found"));
    }

    /** Called once the journey has no PENDING days left — see
     * JourneyService.recordAction()'s return value. Mirrors db.py's
     * record_action() setting habits.status='completed' inline. */
    @Transactional
    public void markCompleted(Habit habit) {
        habit.setStatus(HabitStatus.COMPLETED);
        habitRepository.save(habit);
    }

    /**
     * Port of main.py's POST /api/habits/{id}/schedule (db.py's
     * update_habit_schedule). This is the ONLY way a habit's time_of_day
     * changes after creation — GET /current's 'reschedule' strategy only
     * ever SUGGESTS a time (TimeFormat.suggestAlternateTime) and never
     * calls this itself; only an explicit user action (Change Time, or
     * approving a suggestion) reaches this method.
     */
    @Transactional
    public Habit updateSchedule(String deviceId, Long id, String timeOfDay) {
        Habit habit = getHabit(deviceId, id);
        if (!TIME_OF_DAY.matcher(timeOfDay).matches()) {
            throw new InvalidRequestException("time_of_day must be 'HH:MM' 24h");
        }
        habit.setTimeOfDay(timeOfDay);
        return habitRepository.save(habit);
    }

    /**
     * Port of main.py's POST /api/habits/{id}/continue: the Day-21 "start
     * another 21 days" choice. When no override text is given (mobile
     * never sends one), reuses the same name/time_of_day, and — matching
     * the reference exactly — always requests a literal "21 days" and
     * never mentions duration, so goal_parser resets duration_minutes to
     * its 30-minute default regardless of what the finished habit's
     * duration was. Preserved as-is rather than "fixed", since this is a
     * faithful port of the reference's own (slightly quirky) behavior.
     */
    @Transactional
    public Habit continueHabit(String deviceId, Long id, String overrideText) {
        Habit habit = getHabit(deviceId, id);
        String text = (overrideText != null && !overrideText.isBlank())
                ? overrideText
                : habit.getName() + " every day at " + habit.getTimeOfDay() + " for 21 days";
        return createFromText(deviceId, text);
    }

    /**
     * Port of main.py's POST /api/habits/{id}/stop (db.py's
     * update_habit_status). Deliberately does NOT 404 on an unknown id —
     * the reference's UPDATE just silently affects zero rows and still
     * returns {"ok": true}; preserved for fidelity even though a stricter
     * check would arguably be better.
     */
    @Transactional
    public void stopHabit(String deviceId, Long id) {
        habitRepository.findByIdAndDeviceId(id, deviceId).ifPresent(habit -> {
            habit.setStatus(HabitStatus.STOPPED);
            habitRepository.save(habit);
        });
    }

    /** Port of main.py's DELETE /api/habits/{id} (db.py's delete_habit):
     * hard delete, day rows first, then the habit itself. */
    @Transactional
    public void deleteHabit(String deviceId, Long id) {
        getHabit(deviceId, id); // 404 if missing or owned by a different device
        journeyService.deleteAllDays(id);
        habitRepository.deleteById(id);
    }
}
