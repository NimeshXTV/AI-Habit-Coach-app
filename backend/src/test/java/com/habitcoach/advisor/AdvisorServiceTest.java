package com.habitcoach.advisor;

import com.habitcoach.habit.Habit;
import com.habitcoach.habit.HabitService;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.profile.Gender;
import com.habitcoach.profile.ProfileService;
import com.habitcoach.profile.UserProfile;
import com.habitcoach.web.InvalidRequestException;
import com.habitcoach.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AdvisorService's orchestration: ownership delegation,
 * context-building from existing facts utilities, history bounding, and
 * (most importantly, per the current no-LLM-yet requirement) that an
 * AdvisorUnavailableException from the provider maps to an explicit
 * available=false response rather than any fabricated reply. Uses mocked
 * collaborators, same style as CoachingServiceTest — no Spring context
 * needed.
 */
class AdvisorServiceTest {

    private HabitService habitService;
    private JourneyService journeyService;
    private ProfileService profileService;
    private AdvisorProvider advisorProvider;
    private AdvisorService advisorService;

    @BeforeEach
    void setUp() {
        habitService = mock(HabitService.class);
        journeyService = mock(JourneyService.class);
        profileService = mock(ProfileService.class);
        advisorProvider = mock(AdvisorProvider.class);
        advisorService = new AdvisorService(habitService, journeyService, profileService, advisorProvider);
    }

    /** Habit's id is DB-generated (no constructor accepts it) — mocked here
     * rather than persisted, since this is a pure unit test of
     * AdvisorService's own orchestration, not a repository/HTTP test (see
     * AdvisorControllerTest for the end-to-end version). */
    private static Habit habit(long id, String name, String emoji, int totalDays) {
        Habit habit = mock(Habit.class);
        when(habit.getId()).thenReturn(id);
        when(habit.getName()).thenReturn(name);
        when(habit.getEmoji()).thenReturn(emoji);
        when(habit.getTotalDays()).thenReturn(totalDays);
        return habit;
    }

    private static HabitDay dayRow(int dayNumber, DayStatus status, String feedbackReason, String strategy) {
        HabitDay day = new HabitDay(1L, dayNumber);
        day.setStatus(status);
        day.setFeedbackReason(feedbackReason);
        day.setInterventionStrategy(strategy);
        return day;
    }

    @Test
    void blankMessageIsRejectedBeforeTouchingAnyCollaborator() {
        AdvisorMessageRequest request = new AdvisorMessageRequest("   ", null);

        assertThatThrownBy(() -> advisorService.sendMessage("device-1", 1L, request))
                .isInstanceOf(InvalidRequestException.class);

        verify(habitService, never()).getHabit(anyString(), anyLong());
    }

    @Test
    void ownershipCheckIsDelegatedToTheExistingHabitServiceMechanism() {
        when(habitService.getHabit("a-different-device", 1L)).thenThrow(new NotFoundException("habit not found"));

        assertThatThrownBy(() -> advisorService.sendMessage("a-different-device", 1L,
                new AdvisorMessageRequest("hello", null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unavailableProviderProducesAnExplicitUnavailableResponseNotAFakeReply() {
        Habit h = habit(1L, "Gym", "🏋️", 21);
        when(habitService.getHabit("device-1", 1L)).thenReturn(h);
        when(journeyService.getDays(1L)).thenReturn(List.of());
        when(profileService.getProfile("device-1")).thenReturn(Optional.empty());
        when(advisorProvider.respond(any())).thenThrow(new AdvisorUnavailableException("not configured yet"));

        AdvisorMessageResponse response = advisorService.sendMessage("device-1", 1L,
                new AdvisorMessageRequest("How am I doing?", null));

        assertThat(response.available()).isFalse();
        assertThat(response.replyText()).isNull();
        assertThat(response.unavailableReason()).isEqualTo("not configured yet");
    }

    @Test
    void contextIsBuiltFromExistingHistoryAndProfileFactsNotDuplicatedLogic() {
        Habit h = habit(1L, "Gym", "🏋️", 21);
        when(habitService.getHabit("device-1", 1L)).thenReturn(h);
        List<HabitDay> days = List.of(
                dayRow(1, DayStatus.DONE, null, "encouragement"),
                dayRow(2, DayStatus.MISSED, "no_time", "reduce_task"),
                dayRow(3, DayStatus.PENDING, null, null)
        );
        when(journeyService.getDays(1L)).thenReturn(days);
        UserProfile profile = new UserProfile("device-1", "Nimesh", 27, Gender.MALE);
        when(profileService.getProfile("device-1")).thenReturn(Optional.of(profile));
        when(advisorProvider.respond(any())).thenThrow(new AdvisorUnavailableException("not configured yet"));

        advisorService.sendMessage("device-1", 1L, new AdvisorMessageRequest("What should I do?", null));

        ArgumentCaptor<AdvisorContext> captor = ArgumentCaptor.forClass(AdvisorContext.class);
        verify(advisorProvider).respond(captor.capture());
        AdvisorContext ctx = captor.getValue();

        assertThat(ctx.habitId()).isEqualTo(1L);
        assertThat(ctx.habitName()).isEqualTo("Gym");
        assertThat(ctx.dayNumber()).isEqualTo(3);
        assertThat(ctx.totalDays()).isEqualTo(21);
        assertThat(ctx.currentStreak()).isEqualTo(0); // last reported day was MISSED
        assertThat(ctx.consecutiveMissed()).isEqualTo(1);
        assertThat(ctx.recentFeedbackReasons()).containsExactly("didn't have enough time");
        assertThat(ctx.lastStrategy()).isEqualTo("reduce_task");
        assertThat(ctx.userName()).isEqualTo("Nimesh");
        assertThat(ctx.ageGroup()).isEqualTo("adult");
        assertThat(ctx.userGender()).isEqualTo("male");
        assertThat(ctx.userMessage()).isEqualTo("What should I do?");
    }

    @Test
    void noProfileYetLeavesProfileFactsNull() {
        Habit h = habit(1L, "Gym", "🏋️", 21);
        when(habitService.getHabit("device-1", 1L)).thenReturn(h);
        when(journeyService.getDays(1L)).thenReturn(List.of());
        when(profileService.getProfile("device-1")).thenReturn(Optional.empty());
        when(advisorProvider.respond(any())).thenThrow(new AdvisorUnavailableException("x"));

        advisorService.sendMessage("device-1", 1L, new AdvisorMessageRequest("hi", null));

        ArgumentCaptor<AdvisorContext> captor = ArgumentCaptor.forClass(AdvisorContext.class);
        verify(advisorProvider).respond(captor.capture());
        assertThat(captor.getValue().userName()).isNull();
        assertThat(captor.getValue().ageGroup()).isNull();
        assertThat(captor.getValue().userGender()).isNull();
    }

    @Test
    void historyLongerThanTheCapIsTruncatedToTheMostRecentTurns() {
        Habit h = habit(1L, "Gym", "🏋️", 21);
        when(habitService.getHabit("device-1", 1L)).thenReturn(h);
        when(journeyService.getDays(1L)).thenReturn(List.of());
        when(profileService.getProfile("device-1")).thenReturn(Optional.empty());
        when(advisorProvider.respond(any())).thenThrow(new AdvisorUnavailableException("x"));

        List<AdvisorTurn> longHistory = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            longHistory.add(new AdvisorTurn(i % 2 == 0 ? "user" : "advisor", "turn " + i));
        }

        advisorService.sendMessage("device-1", 1L, new AdvisorMessageRequest("latest", longHistory));

        ArgumentCaptor<AdvisorContext> captor = ArgumentCaptor.forClass(AdvisorContext.class);
        verify(advisorProvider).respond(captor.capture());
        List<AdvisorTurn> passed = captor.getValue().history();
        assertThat(passed).hasSize(AdvisorService.MAX_HISTORY_TURNS);
        assertThat(passed.get(passed.size() - 1).text()).isEqualTo("turn 29");
    }
}
