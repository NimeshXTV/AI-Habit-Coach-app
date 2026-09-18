import type { FeedbackReason, HabitDay } from './types';

/**
 * Deterministic, on-device port of the backend's coaching rules —
 * StrategySelector.java, FallbackTemplates.java, Phase.java, and the
 * ResponseKind-selection half of CoachingService.generateActionResponse().
 * Used ONLY when Spring Boot/Strands are unreachable (see api.ts); when
 * online, the server's own implementation remains authoritative and this
 * file is never consulted. Strands is never approximated here — offline
 * coaching is plain template text, not a smaller AI model (see
 * CLAUDE_CONTEXT.md's explicit "do not add an on-device generative AI
 * model" instruction).
 */

export type InterventionStrategy =
  | 'encouragement' | 'reduce_task' | 'reinforcement' | 'accountability' | 'reschedule' | 'reflection';

export type ResponseKind =
  | 'completion_first' | 'completion_final' | 'completion_recovery' | 'completion_milestone' | 'completion_plain'
  | 'miss_single' | 'miss_consecutive';

type Phase = 'getting_started' | 'building_consistency' | 'handling_resistance' | 'reinforcement' | 'completion';

/** Immutable view of a day used for strategy computation — mirrors
 * DaySnapshot.java, including the "status can be synthetically forced to
 * snoozed for the current day" trick (see buildHistory below). */
export interface DaySnapshot {
  dayNumber: number;
  status: 'pending' | 'done' | 'missed' | 'snoozed';
  feedbackReason: string | null;
  snoozeCount: number | null;
  interventionStrategy: string | null;
}

const FEEDBACK_LABELS: Record<string, string> = {
  too_tired: 'too tired',
  no_time: "didn't have enough time",
  forgot: 'forgot',
  something_came_up: 'something came up',
  not_feeling_it: "didn't feel like doing it",
  other: 'something else',
};

function labelOrDefault(reason: string | null | undefined, fallback: string): string {
  if (!reason) return fallback;
  return FEEDBACK_LABELS[reason] ?? fallback;
}

export function to12Hour(timeOfDay: string): string {
  const [h, m] = timeOfDay.split(':').map(Number);
  const period = h >= 12 ? 'PM' : 'AM';
  let hour12 = h % 12;
  if (hour12 === 0) hour12 = 12;
  return m === 0 ? `${hour12} ${period}` : `${hour12}:${String(m).padStart(2, '0')} ${period}`;
}

function phaseOf(dayNumber: number, totalDays: number): Phase {
  const ratio = dayNumber / totalDays;
  if (ratio <= 3 / 21) return 'getting_started';
  if (ratio <= 7 / 21) return 'building_consistency';
  if (ratio <= 14 / 21) return 'handling_resistance';
  if (ratio <= 18 / 21) return 'reinforcement';
  return 'completion';
}

function recent<T>(items: T[], n: number): T[] {
  if (items.length === 0) return [];
  return items.slice(Math.max(0, items.length - n));
}

export function currentStreak(days: DaySnapshot[]): number {
  let count = 0;
  for (let i = days.length - 1; i >= 0; i--) {
    if (days[i].status === 'done') count++;
    else if (days[i].status === 'missed') break;
  }
  return count;
}

export function consecutiveMissed(days: DaySnapshot[]): number {
  let count = 0;
  for (let i = days.length - 1; i >= 0; i--) {
    if (days[i].status === 'missed') count++;
    else if (days[i].status === 'done') break;
  }
  return count;
}

function repeatedReason(days: DaySnapshot[], reason: string): boolean {
  return recent(days, 4).filter((d) => d.feedbackReason === reason).length >= 2;
}

export function chooseStrategy(dayNumber: number, totalDays: number, days: DaySnapshot[]): InterventionStrategy {
  const phase = phaseOf(dayNumber, totalDays);
  const lastDay = days.length ? days[days.length - 1] : null;

  if (dayNumber === 1 && lastDay === null) return 'encouragement';

  if (repeatedReason(days, 'no_time') || repeatedReason(days, 'too_tired')) return 'reschedule';

  if (consecutiveMissed(days) >= 2) return 'reflection';

  if (lastDay && (lastDay.status === 'missed' || lastDay.status === 'snoozed')) {
    const reason = lastDay.feedbackReason;
    if (reason === 'not_feeling_it' || reason === 'forgot') return 'reduce_task';
    return phase === 'getting_started' || phase === 'handling_resistance' ? 'reduce_task' : 'accountability';
  }

  const missCount = recent(days, 5).filter((d) => d.status === 'missed' || d.status === 'snoozed').length;
  if (missCount >= 3) return 'reflection';

  if (dayNumber === 7 || dayNumber === 14 || dayNumber === 21 || phase === 'reinforcement') return 'reinforcement';

  return 'encouragement';
}

/** Port of CoachingService.buildHistory(): every day strictly before
 * dayNumber, plus (if today's own last action was "snoozed") one
 * synthetic trailing entry forced to snoozed status — never persisted,
 * only used to let today's snooze immediately influence strategy choice. */
export function buildHistory(dayNumber: number, allDays: HabitDay[]): DaySnapshot[] {
  const toSnapshot = (d: HabitDay): DaySnapshot => ({
    dayNumber: d.day_number,
    status: d.status,
    feedbackReason: d.feedback_reason,
    snoozeCount: d.snooze_count,
    interventionStrategy: d.intervention_strategy,
  });
  const history = allDays.filter((d) => d.day_number < dayNumber).map(toSnapshot);
  const currentRecord = allDays.find((d) => d.day_number === dayNumber);
  if (currentRecord && currentRecord.action === 'snoozed') {
    history.push({ ...toSnapshot(currentRecord), status: 'snoozed' });
  }
  return history;
}

/** Port of JourneyService.currentDayNumber(): the first pending day, else
 * the last day, else 1. */
export function currentDayNumber(allDaysAscending: HabitDay[]): number {
  for (const day of allDaysAscending) {
    if (day.status === 'pending') return day.day_number;
  }
  if (allDaysAscending.length === 0) return 1;
  return allDaysAscending[allDaysAscending.length - 1].day_number;
}

/** Port of FallbackTemplates.renderIntervention() — deterministic, no
 * randomness beyond the same two-option phrase pick the backend does
 * (kept, since it's cosmetic wording variety, not a behavior difference). */
export function renderIntervention(
  habitName: string, dayNumber: number, totalDays: number, durationMinutes: number,
  timeOfDay: string, strategy: InterventionStrategy, history: DaySnapshot[]
): string {
  const completed = history.filter((d) => d.status === 'done').length;
  const name = habitName.toLowerCase();
  const lastDay = history.length ? history[history.length - 1] : null;
  const reasonLabel = lastDay ? labelOrDefault(lastDay.feedbackReason, '') : '';

  switch (strategy) {
    case 'encouragement': {
      const options = dayNumber === 1
        ? [
          `Let's get started. Don't think about all ${totalDays} days — just focus on ${name} today.`,
          `Day 1 of ${totalDays}. Keep it simple: just begin. That's the whole goal today.`,
        ]
        : [
          `Day ${dayNumber} of ${totalDays}. You've completed ${completed} so far — keep the momentum going.`,
          `You're on day ${dayNumber}. ${completed} days down already. Let's add one more.`,
        ];
      return options[Math.floor(Math.random() * options.length)];
    }
    case 'reduce_task': {
      const attempts = lastDay?.snoozeCount ?? 0;
      const shrink = Math.max(5, Math.floor(durationMinutes / (2 + attempts)));
      if (reasonLabel === "didn't feel like doing it" || reasonLabel === 'forgot') {
        return `No pressure about the full ${durationMinutes} minutes today. Just do ${shrink} minutes of ${name} — that's it.`;
      }
      return `You've struggled with this recently. Forget the full session — just commit to ${shrink} minutes of ${name} today.`;
    }
    case 'reinforcement': {
      if (dayNumber >= totalDays - 2) {
        return `You're almost at the finish line — day ${dayNumber} of ${totalDays}, ${completed} days completed. Let's close this out strong.`;
      }
      const pct = dayNumber > 1 ? Math.floor((100 * completed) / Math.max(1, dayNumber - 1)) : 0;
      return `You've completed ${completed} of the last ${dayNumber - 1} days (${pct}%). That's real progress on ${name} — keep it up.`;
    }
    case 'accountability':
      return `You've postponed ${name} a couple of times now. Let's take one small, concrete step today instead of skipping again.`;
    case 'reschedule': {
      const reason = reasonLabel || 'trouble with the timing';
      return `${to12Hour(timeOfDay)} hasn't been working well for ${name} lately — you've mentioned ${reason} more than once. Want to try a different time?`;
    }
    case 'reflection':
      return `You've missed a few ${name} sessions this week. What's been getting in the way — is it the time, the task size, or something else?`;
  }
}

/** Port of CoachingService.generateActionResponse()'s ResponseKind
 * selection (done/missed only — never called for snoozed, matching the
 * backend). */
export function chooseResponseKind(
  action: 'done' | 'missed', dayNumber: number, totalDays: number, allDaysAfter: HabitDay[]
): { kind: ResponseKind; consecutiveMissed: number } {
  const snapshots: DaySnapshot[] = allDaysAfter.map((d) => ({
    dayNumber: d.day_number, status: d.status, feedbackReason: d.feedback_reason,
    snoozeCount: d.snooze_count, interventionStrategy: d.intervention_strategy,
  }));
  const consecutive = consecutiveMissed(snapshots);
  const isMilestone = dayNumber === 7 || dayNumber === 14 || dayNumber === totalDays;
  const priorDay = allDaysAfter.find((d) => d.day_number === dayNumber - 1);
  const recovered = priorDay?.status === 'missed' && action === 'done';

  let kind: ResponseKind;
  if (action === 'done') {
    if (dayNumber === totalDays) kind = 'completion_final';
    else if (dayNumber === 1) kind = 'completion_first';
    else if (recovered) kind = 'completion_recovery';
    else if (isMilestone) kind = 'completion_milestone';
    else kind = 'completion_plain';
  } else {
    kind = consecutive >= 2 ? 'miss_consecutive' : 'miss_single';
  }
  return { kind, consecutiveMissed: consecutive };
}

/** Port of FallbackTemplates.renderActionResponse() — verbatim text. */
export function renderActionResponse(kind: ResponseKind, habitName: string, day: number, total: number, completed: number): string {
  const name = habitName.toLowerCase();
  switch (kind) {
    case 'completion_first':
      return "You showed up. That's the hardest part of starting. Day 1 complete — you've officially begun.";
    case 'completion_final':
      return `You made it through all ${total} days of ${name}. You kept coming back to this, and that's a real accomplishment. Take a moment to be proud of it.`;
    case 'completion_recovery':
      return "You came back today — and that's what matters. One missed day didn't stop you. You're back on track.";
    case 'completion_milestone':
      return `Day ${day} done, ${completed} completed so far — real momentum on ${name}. Be proud of showing up today.`;
    case 'completion_plain':
      return `Day ${day} complete. That's ${completed} days on ${name} now — keep it going.`;
    case 'miss_consecutive':
      return "We've missed two days in a row. No guilt — let's figure out what's getting in the way and make tomorrow easier.";
    case 'miss_single':
      return "That's okay. One missed day doesn't erase the work you've already done. Let's get back on track tomorrow.";
  }
}

/** Port of TimeFormat.suggestAlternateTime(): a concrete one-hour-later
 * alternative offered alongside a reschedule suggestion. */
export function suggestAlternateTime(timeOfDay: string): string {
  const [h, m] = timeOfDay.split(':').map(Number);
  const nextHour = (h + 1) % 24;
  return `${String(nextHour).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** Port of FallbackTemplates.renderSummary() — shown once a journey is
 * finished (completed/stopped), verbatim text. */
export function renderSummary(habitName: string, completed: number, totalDays: number): string {
  return `You completed ${completed} out of ${totalDays} days of ${habitName.toLowerCase()}. Want to continue this habit, start a new 21-day challenge, or stop here?`;
}

export type { FeedbackReason };
