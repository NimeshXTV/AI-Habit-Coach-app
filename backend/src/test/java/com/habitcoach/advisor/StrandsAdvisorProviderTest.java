package com.habitcoach.advisor;

import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsResponse;
import com.habitcoach.strands.StrandsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StrandsAdvisorProvider's orchestration: request mapping
 * (AdvisorContext -> {key, facts} sent to StrandsClient.generate), the
 * successful-response path, and — per the hard "never fabricate an AI
 * response" requirement — that any failure (network error, timeout,
 * unreachable service, or a blank/empty Strands reply) delegates to a real
 * UnavailableAdvisorProvider rather than synthesizing anything. Mocks only
 * StrandsClient, same convention as CoachingServiceTest; uses a real
 * UnavailableAdvisorProvider (it has no dependencies and is itself
 * deterministic) so the fallback assertions exercise the actual class, not
 * a stand-in for it. Whatever model strands-service actually calls
 * (STRANDS_MODEL_PROVIDER=bedrock_mantle -> OpenAIResponsesModel -> Bedrock
 * Mantle) is irrelevant here — this class only depends on StrandsClient's
 * {key, facts} -> StrandsResponse contract.
 */
class StrandsAdvisorProviderTest {

    private StrandsClient strandsClient;
    private UnavailableAdvisorProvider fallbackProvider;
    private StrandsAdvisorProvider provider;

    @BeforeEach
    void setUp() {
        strandsClient = mock(StrandsClient.class);
        fallbackProvider = new UnavailableAdvisorProvider();
        provider = new StrandsAdvisorProvider(strandsClient, fallbackProvider);
    }

    private static AdvisorContext context(List<AdvisorTurn> history) {
        return new AdvisorContext(
                1L, "Gym", "🏋️", 5, 21,
                3, 0, List.of("didn't have enough time"), "reduce_task",
                "Nimesh", "adult", "male", "How am I doing?", history
        );
    }

    // ---- 1. successful response ----

    @Test
    void successfulStrandsResponseIsReturnedVerbatim() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("Keep it up — you're doing great!", "strands"));

        String reply = provider.respond(context(List.of()));

        assertThat(reply).isEqualTo("Keep it up — you're doing great!");
    }

    /** First Advisor turn: no prior conversation, so no conversation_so_far
     * fact should be sent at all (see requestMappingOmitsNullProfileFactsWhenNoProfileExists
     * for the analogous no-profile-yet case). */
    @Test
    void requestMappingIncludesHabitContextAndLatestMessage() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));

        provider.respond(context(List.of()));

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(eq("advisor"), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();

        assertThat(facts.get("habit_name")).isEqualTo("Gym");
        assertThat(facts.get("day")).isEqualTo(5);
        assertThat(facts.get("total_days")).isEqualTo(21);
        assertThat(facts.get("current_streak")).isEqualTo(3);
        assertThat(facts.get("consecutive_missed")).isEqualTo(0);
        assertThat(facts.get("recent_feedback_reasons")).isEqualTo("didn't have enough time");
        assertThat(facts.get("last_strategy")).isEqualTo("reduce_task");
        assertThat(facts.get("user_name")).isEqualTo("Nimesh");
        assertThat(facts.get("age_group")).isEqualTo("adult");
        assertThat(facts.get("user_gender")).isEqualTo("male");
        assertThat(facts.get("user_message")).isEqualTo("How am I doing?");
        assertThat(facts).doesNotContainKey("conversation_so_far");
    }

    /** Second Advisor turn: exactly one prior exchange, so the prior
     * assistant reply must be replayed as plain text — this is the ONLY
     * conversation-history mechanism this application uses; the Strands
     * SDK's own Agent-level message history is never involved (see
     * strands-service/agent.py's _build_agent() javadoc for why: a shared,
     * cross-call Agent instance was the actual root cause of "reasoningContent
     * is not yet supported in multi-turn conversations" against a real
     * physical device, since that instance is a python-level concern with
     * no Java-side equivalent to leak — this test pins down that Java's own
     * request stays a single self-contained {key, facts} call regardless). */
    @Test
    void secondAdvisorTurnIncludesThePriorAssistantResponseVerbatim() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));
        List<AdvisorTurn> history = List.of(
                new AdvisorTurn("user", "I missed today"),
                new AdvisorTurn("advisor", "That's okay, let's plan for tomorrow.")
        );

        provider.respond(context(history));

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(eq("advisor"), factsCaptor.capture());
        Object conversation = factsCaptor.getValue().get("conversation_so_far");

        assertThat(conversation).isEqualTo("user: I missed today\nadvisor: That's okay, let's plan for tomorrow.");
    }

    /** Multi-turn: several exchanges, not just one — proves ordering is
     * preserved and every turn (not just the most recent) is represented as
     * plain role/text, never as anything resembling a reasoning/internal
     * content block (AdvisorTurn structurally has only role+text — there is
     * no field for reasoning content to ever occupy). */
    @Test
    void multiTurnHistoryIsFormattedInChronologicalOrderAsPlainTextOnly() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));
        List<AdvisorTurn> history = List.of(
                new AdvisorTurn("user", "I missed today"),
                new AdvisorTurn("advisor", "That's okay, let's plan for tomorrow."),
                new AdvisorTurn("user", "What time should I try?"),
                new AdvisorTurn("advisor", "How about right after breakfast?"),
                new AdvisorTurn("user", "Sounds good."),
                new AdvisorTurn("advisor", "Great — I'll check in tomorrow.")
        );

        provider.respond(context(history));

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(eq("advisor"), factsCaptor.capture());
        String conversation = (String) factsCaptor.getValue().get("conversation_so_far");

        assertThat(conversation).isEqualTo(String.join("\n",
                "user: I missed today",
                "advisor: That's okay, let's plan for tomorrow.",
                "user: What time should I try?",
                "advisor: How about right after breakfast?",
                "user: Sounds good.",
                "advisor: Great — I'll check in tomorrow."));
    }

    /** Bounded history: AdvisorService already truncates to MAX_HISTORY_TURNS
     * before StrandsAdvisorProvider ever sees it (see
     * AdvisorServiceTest.historyLongerThanTheCapIsTruncatedToTheMostRecentTurns)
     * — this test confirms StrandsAdvisorProvider faithfully includes every
     * turn it's given up to that bound, without any further silent
     * truncation of its own that could drop context unexpectedly. */
    @Test
    void everyTurnUpToTheGivenBoundIsIncludedWithoutFurtherTruncation() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));
        List<AdvisorTurn> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new AdvisorTurn(i % 2 == 0 ? "user" : "advisor", "turn " + i));
        }

        provider.respond(context(history));

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(eq("advisor"), factsCaptor.capture());
        String conversation = (String) factsCaptor.getValue().get("conversation_so_far");

        assertThat(conversation.split("\n")).hasSize(20);
        assertThat(conversation).contains("turn 0").contains("turn 19");
    }

    /** Direct analog, at this layer, of the actual root-cause bug: proves
     * StrandsAdvisorProvider itself holds no mutable cross-call state, so a
     * request for one habit/conversation can never leak into the facts sent
     * for a later, unrelated one (the actual leak was in strands-service's
     * shared strands.Agent instance — see agent.py — but this pins down
     * that this Java layer was never and is still not part of the
     * problem). */
    @Test
    void consecutiveCallsDoNotLeakContextBetweenUnrelatedRequests() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));

        AdvisorContext gymContext = context(List.of(new AdvisorTurn("advisor", "Gym-specific prior reply")));
        AdvisorContext studyContext = new AdvisorContext(
                2L, "Study", "📚", 1, 21,
                0, 0, List.of(), null,
                null, null, null, "How's study going?", List.of()
        );

        provider.respond(gymContext);
        provider.respond(studyContext);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient, org.mockito.Mockito.times(2)).generate(eq("advisor"), factsCaptor.capture());
        Map<String, Object> secondCallFacts = factsCaptor.getAllValues().get(1);

        assertThat(secondCallFacts.get("habit_name")).isEqualTo("Study");
        assertThat(secondCallFacts.get("user_message")).isEqualTo("How's study going?");
        assertThat(secondCallFacts).doesNotContainKey("conversation_so_far");
        assertThat(secondCallFacts.values()).noneMatch(v -> "Gym-specific prior reply".equals(v)
                || (v instanceof String s && s.contains("Gym-specific prior reply")));
    }

    @Test
    void requestMappingOmitsNullProfileFactsWhenNoProfileExists() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("ok", "strands"));
        AdvisorContext ctx = new AdvisorContext(
                1L, "Gym", "🏋️", 1, 21,
                0, 0, List.of(), null,
                null, null, null, "hi", List.of()
        );

        provider.respond(ctx);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(eq("advisor"), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();

        assertThat(facts).doesNotContainKeys("user_name", "age_group", "user_gender", "last_strategy",
                "recent_feedback_reasons", "conversation_so_far");
    }

    // ---- 2. StrandsClient failure -> fallback invoked ----

    @Test
    void strandsClientFailureFallsBackToUnavailableAdvisorProvider() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenThrow(new StrandsUnavailableException("Strands service call failed: connection refused", null));

        assertThatThrownBy(() -> provider.respond(context(List.of())))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessage("The Habit Advisor isn't connected to an AI service yet.");
    }

    @Test
    void strandsClientTimeoutFallsBackToUnavailableAdvisorProvider() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenThrow(new StrandsUnavailableException("Strands service call failed: timeout", new RuntimeException("timeout")));

        assertThatThrownBy(() -> provider.respond(context(List.of())))
                .isInstanceOf(AdvisorUnavailableException.class);
    }

    // ---- 3. Blank/unusable Strands response -> fallback invoked ----

    @Test
    void blankStrandsReplyIsTreatedAsUnusableAndFallsBack() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("   ", "strands"));

        assertThatThrownBy(() -> provider.respond(context(List.of())))
                .isInstanceOf(AdvisorUnavailableException.class);
    }

    @Test
    void emptyStrandsReplyIsTreatedAsUnusableAndFallsBack() {
        when(strandsClient.generate(eq("advisor"), anyMap()))
                .thenReturn(new StrandsResponse("", "strands"));

        assertThatThrownBy(() -> provider.respond(context(List.of())))
                .isInstanceOf(AdvisorUnavailableException.class);
    }

    // ---- fallback provider behavior: real template response, never a fake AI reply ----

    @Test
    void fallbackNeverFabricatesAnAiReply() {
        when(strandsClient.generate(any(), anyMap()))
                .thenThrow(new StrandsUnavailableException("down", null));

        // The only thing StrandsAdvisorProvider does on failure is delegate
        // to UnavailableAdvisorProvider.respond(), which always throws —
        // there is no code path here that returns a String when Strands is
        // unavailable, so a fabricated reply is structurally impossible.
        // The exact message is UnavailableAdvisorProvider's own, unmodified
        // text — proving the real fallback class was invoked, not a stub.
        assertThatThrownBy(() -> provider.respond(context(List.of())))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessage("The Habit Advisor isn't connected to an AI service yet.");
    }
}
