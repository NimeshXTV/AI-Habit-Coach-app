package com.habitcoach.coaching;

import com.habitcoach.habit.Habit;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.profile.AgeGroup;
import com.habitcoach.profile.ProfileService;
import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsResponse;
import com.habitcoach.strands.StrandsUnavailableException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the adaptive coaching flow: builds the properly-scoped
 * history (port of main.py's _build_state), picks a strategy
 * (StrategySelector, fully deterministic), builds the facts payload (port
 * of intervention_engine.py's _facts()), and calls the stateless Strands
 * service for the actual wording — falling back to a Java-rendered
 * template if Strands is unreachable. Strands never sees or owns any
 * persistent state; every call is a self-contained {key, facts} request.
 */
@Service
public class CoachingService {

    private final StrandsClient strandsClient;
    private final ProfileService profileService;

    public CoachingService(StrandsClient strandsClient, ProfileService profileService) {
        this.strandsClient = strandsClient;
        this.profileService = profileService;
    }

    public InterventionResult generateIntervention(Habit habit, int dayNumber, List<HabitDay> allDays) {
        List<DaySnapshot> history = buildHistory(dayNumber, allDays);
        InterventionStrategy strategy = StrategySelector.choose(dayNumber, habit.getTotalDays(), history);
        Map<String, Object> facts = buildFacts(habit, dayNumber, history);

        try {
            StrandsResponse response = strandsClient.generate(strategy.toJson(), facts);
            return new InterventionResult(response.text(), strategy, "strands");
        } catch (StrandsUnavailableException e) {
            String text = FallbackTemplates.renderIntervention(habit.getName(), dayNumber, habit.getTotalDays(),
                    habit.getDurationMinutes(), habit.getTimeOfDay(), strategy, history);
            return new InterventionResult(text, strategy, "template");
        }
    }

    public SummaryResult generateSummary(Habit habit, List<HabitDay> allDays) {
        int completed = (int) allDays.stream().filter(d -> d.getStatus() == DayStatus.DONE).count();
        Map<String, Object> facts = Map.of(
                "name", habit.getName().toLowerCase(),
                "total", habit.getTotalDays(),
                "completed", completed
        );

        try {
            StrandsResponse response = strandsClient.generate("summary", facts);
            return new SummaryResult(response.text(), "strands");
        } catch (StrandsUnavailableException e) {
            return new SummaryResult(FallbackTemplates.renderSummary(habit.getName(), completed, habit.getTotalDays()), "template");
        }
    }

    /**
     * Port of intervention_engine.py's generate_action_response(): the
     * post-ACTION reaction (celebrate a completion, normalize a single
     * miss, proactively address two in a row). Deliberately uses ALL days
     * (allDaysAfter, already updated by JourneyService.recordAction) rather
     * than the strictly-before-current history used for the pre-action
     * nudge — streak/consecutive-miss here must reflect what JUST happened
     * today, not the situation walking into today. Never called for
     * "snoozed" (the next /current call already covers that case).
     */
    public ActionResponseResult generateActionResponse(Habit habit, int dayNumber, String action,
                                                         List<HabitDay> allDaysAfter) {
        int completed = (int) allDaysAfter.stream().filter(d -> d.getStatus() == DayStatus.DONE).count();
        List<DaySnapshot> allSnapshots = allDaysAfter.stream().map(DaySnapshot::from).toList();
        int consecutiveMissed = StrategySelector.consecutiveMissed(allSnapshots);

        boolean isMilestone = dayNumber == 7 || dayNumber == 14 || dayNumber == habit.getTotalDays();
        HabitDay thisDay = allDaysAfter.stream().filter(d -> d.getDayNumber() == dayNumber).findFirst().orElse(null);
        HabitDay priorDay = allDaysAfter.stream().filter(d -> d.getDayNumber() == dayNumber - 1).findFirst().orElse(null);
        boolean recovered = priorDay != null && priorDay.getStatus() == DayStatus.MISSED && "done".equals(action);

        ResponseKind kind;
        if ("done".equals(action)) {
            if (dayNumber == habit.getTotalDays()) {
                kind = ResponseKind.COMPLETION_FINAL;
            } else if (dayNumber == 1) {
                kind = ResponseKind.COMPLETION_FIRST;
            } else if (recovered) {
                kind = ResponseKind.COMPLETION_RECOVERY;
            } else if (isMilestone) {
                kind = ResponseKind.COMPLETION_MILESTONE;
            } else {
                kind = ResponseKind.COMPLETION_PLAIN;
            }
        } else {
            kind = consecutiveMissed >= 2 ? ResponseKind.MISS_CONSECUTIVE : ResponseKind.MISS_SINGLE;
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", habit.getName().toLowerCase());
        facts.put("day", dayNumber);
        facts.put("total", habit.getTotalDays());
        facts.put("completed", completed);
        facts.put("current_streak", StrategySelector.currentStreak(allSnapshots));
        facts.put("consecutive_missed", consecutiveMissed);
        facts.put("reason", FeedbackLabels.labelOrDefault(thisDay != null ? thisDay.getFeedbackReason() : null, ""));
        addProfileFacts(facts);

        try {
            StrandsResponse response = strandsClient.generate(kind.toJson(), facts);
            return new ActionResponseResult(response.text(), kind, "strands", consecutiveMissed);
        } catch (StrandsUnavailableException e) {
            String text = FallbackTemplates.renderActionResponse(kind, habit.getName(), dayNumber,
                    habit.getTotalDays(), completed);
            return new ActionResponseResult(text, kind, "template", consecutiveMissed);
        }
    }

    /**
     * Port of main.py's _build_state(): history is every day strictly
     * before dayNumber, plus — if the current day's last recorded action
     * was "snoozed" — one synthetic trailing entry with status forced to
     * SNOOZED. That synthetic entry is never persisted (see DaySnapshot's
     * javadoc); it only exists so the SAME day's snooze immediately
     * influences the next strategy choice, exactly like the reference.
     */
    private List<DaySnapshot> buildHistory(int dayNumber, List<HabitDay> allDays) {
        HabitDay currentRecord = allDays.stream()
                .filter(d -> d.getDayNumber() == dayNumber)
                .findFirst()
                .orElse(null);

        List<DaySnapshot> history = new ArrayList<>();
        for (HabitDay day : allDays) {
            if (day.getDayNumber() < dayNumber) {
                history.add(DaySnapshot.from(day));
            }
        }
        if (currentRecord != null && "snoozed".equals(currentRecord.getAction())) {
            history.add(DaySnapshot.from(currentRecord).asSnoozed());
        }
        return history;
    }

    private Map<String, Object> buildFacts(Habit habit, int dayNumber, List<DaySnapshot> history) {
        DaySnapshot lastDay = history.isEmpty() ? null : history.get(history.size() - 1);
        int completed = (int) history.stream().filter(d -> d.status() == DayStatus.DONE).count();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", habit.getName().toLowerCase());
        facts.put("day", dayNumber);
        facts.put("total", habit.getTotalDays());
        facts.put("completed", completed);
        facts.put("minutes", habit.getDurationMinutes());
        facts.put("time_of_day", TimeFormat.to12Hour(habit.getTimeOfDay()));
        facts.put("reason", FeedbackLabels.labelOrDefault(lastDay != null ? lastDay.feedbackReason() : null,
                "trouble with the timing"));
        facts.put("current_streak", StrategySelector.currentStreak(history));
        facts.put("consecutive_missed", StrategySelector.consecutiveMissed(history));
        facts.put("snooze_count_today", lastDay != null && lastDay.snoozeCount() != null ? lastDay.snoozeCount() : 0);
        facts.put("last_strategy", lastDay != null && lastDay.interventionStrategy() != null
                ? lastDay.interventionStrategy() : "none");
        addProfileFacts(facts);
        return facts;
    }

    /**
     * Adds the onboarding profile (see ProfileService/UserProfile) to a
     * facts payload, when one has been saved — a fresh install with no
     * profile yet simply gets none of these keys, exactly as before this
     * feature existed (see CoachingServiceTest's untouched golden-master
     * assertions). AGE_GROUP is the coarse, deterministic bucket
     * (AgeGroup.of) the SYSTEM_PROMPT in the Strands service is told to use
     * for tone/complexity (child/teen/adult/older_adult) — Spring Boot never
     * writes age-specific coaching text itself, it only hands the AI the
     * fact. USER_GENDER is likewise handed over as a fact, not filtered
     * here: whether/how it's relevant is a per-message judgment call for the
     * AI (per the SYSTEM_PROMPT's explicit no-stereotyping instruction), not
     * something this deterministic layer should decide.
     */
    private void addProfileFacts(Map<String, Object> facts) {
        profileService.getProfile().ifPresent(profile -> {
            facts.put("user_name", profile.getName());
            facts.put("user_age", profile.getAge());
            facts.put("age_group", AgeGroup.of(profile.getAge()).toJson());
            facts.put("user_gender", profile.getGender().toJson());
        });
    }
}
