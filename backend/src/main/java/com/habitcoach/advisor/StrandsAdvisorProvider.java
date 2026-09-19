package com.habitcoach.advisor;

import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsResponse;
import com.habitcoach.strands.StrandsUnavailableException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real Habit Advisor provider: calls the existing StrandsClient (the
 * same HTTP client CoachingService already uses for the deterministic
 * intervention/response engine — no second HTTP client is created here) so
 * the Strands service's model-backed agent answers the user's actual
 * question, with the full habit-specific AdvisorContext (day/streak/profile
 * facts/etc.) and bounded conversation history folded into the {key, facts}
 * request the Strands /generate endpoint already expects. No change to the
 * /generate contract was needed: `key` is just a label the agent's prompt
 * builder (agent.py's build_fact_prompt) turns into a "STRATEGY: ..." line,
 * so a new key ("advisor") works regardless of which model is behind it
 * (see strands-service/agent.py's STRANDS_MODEL_PROVIDER=bedrock_mantle
 * provider — this class is unaware of and unaffected by which model Python
 * actually calls).
 *
 * @Primary over UnavailableAdvisorProvider so AdvisorService's existing
 * single-AdvisorProvider constructor injection picks this one with no
 * change to AdvisorService, AdvisorController, or the mobile contract.
 * Spring's @Primary is used deliberately instead of inventing a new
 * provider-selection mechanism — no such mechanism previously existed in
 * this codebase.
 *
 * On any failure — network error, timeout, non-2xx response (all already
 * normalized to StrandsUnavailableException by StrandsClient.generate), or
 * a blank/empty response text — this delegates to the untouched, unmodified
 * UnavailableAdvisorProvider, which is what actually produces the explicit
 * "unavailable" response AdvisorService already knows how to surface
 * (AdvisorMessageResponse.available=false). Never a fabricated or templated
 * stand-in answer, same rule as before this provider existed (see
 * AdvisorProvider's javadoc) — this class does not itself decide what
 * "unavailable" looks like, it only decides when to defer to the provider
 * that already does.
 */
@Component
@Primary
public class StrandsAdvisorProvider implements AdvisorProvider {

    /** Lookup key sent to the Strands /generate endpoint, distinct from any
     * InterventionStrategy/ResponseKind key CoachingService uses (the Habit
     * Advisor is a different, open-ended chat surface — see
     * AdvisorService's own class javadoc). */
    static final String ADVISOR_KEY = "advisor";

    private final StrandsClient strandsClient;
    private final UnavailableAdvisorProvider fallbackProvider;

    public StrandsAdvisorProvider(StrandsClient strandsClient, UnavailableAdvisorProvider fallbackProvider) {
        this.strandsClient = strandsClient;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public String respond(AdvisorContext context) {
        try {
            StrandsResponse response = strandsClient.generate(ADVISOR_KEY, buildFacts(context));
            String text = response.text() == null ? null : response.text().strip();
            if (text == null || text.isEmpty()) {
                throw new StrandsUnavailableException("Strands service returned an empty advisor reply", null);
            }
            return text;
        } catch (StrandsUnavailableException e) {
            // fallbackProvider.respond() always throws AdvisorUnavailableException
            // (see UnavailableAdvisorProvider) — that propagates unchanged to
            // AdvisorService, which already maps it to an explicit
            // available=false response.
            return fallbackProvider.respond(context);
        }
    }

    private Map<String, Object> buildFacts(AdvisorContext context) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("habit_name", context.habitName());
        facts.put("habit_emoji", context.habitEmoji());
        facts.put("day", context.dayNumber());
        facts.put("total_days", context.totalDays());
        facts.put("current_streak", context.currentStreak());
        facts.put("consecutive_missed", context.consecutiveMissed());
        if (!context.recentFeedbackReasons().isEmpty()) {
            facts.put("recent_feedback_reasons", String.join(", ", context.recentFeedbackReasons()));
        }
        if (context.lastStrategy() != null) {
            facts.put("last_strategy", context.lastStrategy());
        }
        if (context.userName() != null) {
            facts.put("user_name", context.userName());
        }
        if (context.ageGroup() != null) {
            facts.put("age_group", context.ageGroup());
        }
        if (context.userGender() != null) {
            facts.put("user_gender", context.userGender());
        }
        if (!context.history().isEmpty()) {
            facts.put("conversation_so_far", formatHistory(context.history()));
        }
        facts.put("user_message", context.userMessage());
        return facts;
    }

    private String formatHistory(List<AdvisorTurn> history) {
        StringBuilder sb = new StringBuilder();
        for (AdvisorTurn turn : history) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(turn.role()).append(": ").append(turn.text());
        }
        return sb.toString();
    }
}
