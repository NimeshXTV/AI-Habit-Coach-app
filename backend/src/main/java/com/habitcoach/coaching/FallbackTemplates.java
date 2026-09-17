package com.habitcoach.coaching;

import java.util.List;
import java.util.Random;

/**
 * Java-side port of legacy-reference/backend/intervention_engine.py's
 * _render_template() and generate_summary()'s inline fallback string.
 *
 * This is a DIFFERENT fallback layer than the Strands service's own
 * coach_model.py phrase banks: those run INSIDE a successful Strands call
 * (the "local" model provider). This one only fires when the Strands
 * service itself cannot be reached at all (StrandsUnavailableException) —
 * a failure mode that didn't exist in the reference (there, agent.py was
 * an in-process call). Kept at full parity with the reference's fallback
 * text rather than a smaller stub, since "preserve fallback behavior" was
 * an explicit requirement.
 */
final class FallbackTemplates {

    private static final Random RANDOM = new Random();

    private FallbackTemplates() {
    }

    static String renderIntervention(String habitName, int dayNumber, int totalDays, int durationMinutes,
                                      String timeOfDay, InterventionStrategy strategy, List<DaySnapshot> history) {
        int completed = (int) history.stream().filter(d -> d.status() == com.habitcoach.journey.DayStatus.DONE).count();
        String name = habitName;
        DaySnapshot lastDay = history.isEmpty() ? null : history.get(history.size() - 1);
        String reasonLabel = lastDay != null ? FeedbackLabels.labelOrDefault(lastDay.feedbackReason(), "") : "";

        return switch (strategy) {
            case ENCOURAGEMENT -> {
                List<String> options = dayNumber == 1
                        ? List.of(
                        "Let's get started. Don't think about all " + totalDays + " days — just focus on "
                                + name.toLowerCase() + " today.",
                        "Day 1 of " + totalDays + ". Keep it simple: just begin. That's the whole goal today.")
                        : List.of(
                        "Day " + dayNumber + " of " + totalDays + ". You've completed " + completed
                                + " so far — keep the momentum going.",
                        "You're on day " + dayNumber + ". " + completed + " days down already. Let's add one more.");
                yield options.get(RANDOM.nextInt(options.size()));
            }
            case REDUCE_TASK -> {
                int attempts = lastDay != null && lastDay.snoozeCount() != null ? lastDay.snoozeCount() : 0;
                int shrink = Math.max(5, durationMinutes / (2 + attempts));
                if ("didn't feel like doing it".equals(reasonLabel) || "forgot".equals(reasonLabel)) {
                    yield "No pressure about the full " + durationMinutes + " minutes today. Just do " + shrink
                            + " minutes of " + name.toLowerCase() + " — that's it.";
                }
                yield "You've struggled with this recently. Forget the full session — just commit to " + shrink
                        + " minutes of " + name.toLowerCase() + " today.";
            }
            case REINFORCEMENT -> {
                int pct = dayNumber > 1 ? (int) (100.0 * completed / Math.max(1, dayNumber - 1)) : 0;
                if (dayNumber >= totalDays - 2) {
                    yield "You're almost at the finish line — day " + dayNumber + " of " + totalDays + ", "
                            + completed + " days completed. Let's close this out strong.";
                }
                yield "You've completed " + completed + " of the last " + (dayNumber - 1) + " days (" + pct
                        + "%). That's real progress on " + name.toLowerCase() + " — keep it up.";
            }
            case ACCOUNTABILITY -> "You've postponed " + name.toLowerCase()
                    + " a couple of times now. Let's take one small, concrete step today instead of skipping again.";
            case RESCHEDULE -> {
                String reason = reasonLabel.isEmpty() ? "trouble with the timing" : reasonLabel;
                yield TimeFormat.to12Hour(timeOfDay) + " hasn't been working well for " + name.toLowerCase()
                        + " lately — you've mentioned " + reason + " more than once. Want to try a different time?";
            }
            case REFLECTION -> "You've missed a few " + name.toLowerCase()
                    + " sessions this week. What's been getting in the way — is it the time, the task size, or something else?";
        };
    }

    static String renderSummary(String habitName, int completed, int totalDays) {
        return "You completed " + completed + " out of " + totalDays + " days of " + habitName.toLowerCase()
                + ". Want to continue this habit, start a new 21-day challenge, or stop here?";
    }

    /** Ported verbatim from intervention_engine.py's
     * _render_response_template(). */
    static String renderActionResponse(ResponseKind kind, String habitName, int day, int total, int completed) {
        String name = habitName.toLowerCase();
        return switch (kind) {
            case COMPLETION_FIRST ->
                    "You showed up. That's the hardest part of starting. Day 1 complete — you've officially begun.";
            case COMPLETION_FINAL -> "You made it through all " + total + " days of " + name
                    + ". You kept coming back to this, and that's a real accomplishment. Take a moment to be proud of it.";
            case COMPLETION_RECOVERY ->
                    "You came back today — and that's what matters. One missed day didn't stop you. You're back on track.";
            case COMPLETION_MILESTONE -> "Day " + day + " done, " + completed + " completed so far — real momentum on "
                    + name + ". Be proud of showing up today.";
            case COMPLETION_PLAIN -> "Day " + day + " complete. That's " + completed + " days on " + name
                    + " now — keep it going.";
            case MISS_CONSECUTIVE ->
                    "We've missed two days in a row. No guilt — let's figure out what's getting in the way and make tomorrow easier.";
            case MISS_SINGLE ->
                    "That's okay. One missed day doesn't erase the work you've already done. Let's get back on track tomorrow.";
        };
    }
}
