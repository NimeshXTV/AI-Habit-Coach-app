package com.habitcoach.advisor;

import com.habitcoach.coaching.DaySnapshot;
import com.habitcoach.coaching.FeedbackLabels;
import com.habitcoach.coaching.StrategySelector;
import com.habitcoach.habit.Habit;
import com.habitcoach.habit.HabitService;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.profile.AgeGroup;
import com.habitcoach.profile.ProfileService;
import com.habitcoach.web.InvalidRequestException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates a Habit Advisor chat turn: verifies device ownership of the
 * habit (reusing HabitService.getHabit — the same X-Device-Id-scoped 404
 * every other habit route already enforces), builds an AdvisorContext from
 * existing facts-producing utilities (StrategySelector, FeedbackLabels,
 * AgeGroup, ProfileService — the same pieces CoachingService uses, reused
 * rather than duplicated), and calls the currently-registered
 * AdvisorProvider. Deliberately does NOT call CoachModel/FallbackTemplates/
 * StrandsClient — the advisor is a separate, online-only, open-ended chat
 * surface, not another consumer of the deterministic coaching-nudge engine.
 */
@Service
public class AdvisorService {

    /** Defensive server-side cap on how much client-sent history is ever
     * forwarded to a provider — independent of whatever bound the mobile
     * app's own local store applies (see advisorStore.ts), so a future
     * provider's prompt size can never be blown out by a misbehaving or
     * future client. */
    static final int MAX_HISTORY_TURNS = 20;

    private final HabitService habitService;
    private final JourneyService journeyService;
    private final ProfileService profileService;
    private final AdvisorProvider advisorProvider;

    public AdvisorService(HabitService habitService, JourneyService journeyService, ProfileService profileService,
                           AdvisorProvider advisorProvider) {
        this.habitService = habitService;
        this.journeyService = journeyService;
        this.profileService = profileService;
        this.advisorProvider = advisorProvider;
    }

    public AdvisorMessageResponse sendMessage(String deviceId, Long habitId, AdvisorMessageRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new InvalidRequestException("message must not be blank");
        }

        // 404s if this habit doesn't exist or belongs to a different
        // device — the exact same ownership check every other habit-scoped
        // route uses (see HabitController), not a separate mechanism.
        Habit habit = habitService.getHabit(deviceId, habitId);
        List<HabitDay> days = journeyService.getDays(habitId);
        int dayNumber = JourneyService.currentDayNumber(days);

        AdvisorContext context = buildContext(deviceId, habit, dayNumber, days, request);

        try {
            String reply = advisorProvider.respond(context);
            return AdvisorMessageResponse.reply(reply);
        } catch (AdvisorUnavailableException e) {
            return AdvisorMessageResponse.unavailable(e.getMessage());
        }
    }

    private AdvisorContext buildContext(String deviceId, Habit habit, int dayNumber, List<HabitDay> days,
                                         AdvisorMessageRequest request) {
        List<DaySnapshot> reported = days.stream()
                .filter(d -> d.getStatus() != DayStatus.PENDING)
                .map(DaySnapshot::from)
                .toList();

        int currentStreak = StrategySelector.currentStreak(reported);
        int consecutiveMissed = StrategySelector.consecutiveMissed(reported);

        List<String> recentFeedbackReasons = new ArrayList<>();
        for (int i = reported.size() - 1; i >= 0 && recentFeedbackReasons.size() < 3; i--) {
            String reason = reported.get(i).feedbackReason();
            if (reason != null) {
                recentFeedbackReasons.add(0, FeedbackLabels.labelOrDefault(reason, reason));
            }
        }

        String lastStrategy = reported.isEmpty() ? null : reported.get(reported.size() - 1).interventionStrategy();

        String userName = null;
        String ageGroup = null;
        String userGender = null;
        var profile = profileService.getProfile(deviceId);
        if (profile.isPresent()) {
            userName = profile.get().getName();
            ageGroup = AgeGroup.of(profile.get().getAge()).toJson();
            userGender = profile.get().getGender().toJson();
        }

        List<AdvisorTurn> history = request.historyOrEmpty();
        if (history.size() > MAX_HISTORY_TURNS) {
            history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
        }

        return new AdvisorContext(
                habit.getId(), habit.getName(), habit.getEmoji(), dayNumber, habit.getTotalDays(),
                currentStreak, consecutiveMissed, recentFeedbackReasons, lastStrategy,
                userName, ageGroup, userGender, request.message(), history
        );
    }
}
