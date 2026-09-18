import AsyncStorage from '@react-native-async-storage/async-storage';
import { NativeModules, Platform } from 'react-native';
import type { FeedbackReason, Gender, Habit, HabitDay, UserProfile } from './types';
import { currentDayNumber } from './offlineCoach';

const { AlarmModule } = NativeModules;

const KEY_PROFILE = 'habit-coach:store:profile';
const KEY_HABITS = 'habit-coach:store:habits';
const KEY_PENDING_SYNC = 'habit-coach:store:pendingSync';
const habitDaysKey = (habitId: number) => `habit-coach:store:habitDays:${habitId}`;

/**
 * The on-device source of truth for the offline-first core (see
 * CLAUDE_CONTEXT.md's offline-first architecture note). Mirrors whatever
 * Spring Boot last confirmed (habits, habit_days, profile) so the app can
 * launch, show My Challenges/Habit Detail/calendar, and fire+resolve
 * alarms with zero network reachability. It never invents data — every
 * value here is either something a real server response wrote (see
 * api.ts's read paths) or a locally-applied action recorded with the
 * SAME state-transition rules the backend uses (see offlineCoach.ts),
 * queued in `pendingSync` for replay once back online.
 *
 * Deliberately AsyncStorage-backed, not a new embedded database (e.g.
 * expo-sqlite): this project already uses AsyncStorage everywhere else for
 * local persistence (apiConfig.ts, deviceId.ts), the data volume here is
 * tiny (a handful of habits, ~21 day-rows each), and the one
 * piece of state native code (AlarmActionReceiver) must touch without JS
 * alive — the per-day snooze count and the "an action happened" signal —
 * lives in its OWN native-only store instead (NativeEventLog.kt) and is
 * drained into this one on next app foreground (see drainNativeEvents
 * below), rather than two runtimes sharing one file/database.
 */

async function readJson<T>(key: string, fallback: T): Promise<T> {
  try {
    const raw = await AsyncStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

async function writeJson<T>(key: string, value: T): Promise<void> {
  try {
    await AsyncStorage.setItem(key, JSON.stringify(value));
  } catch {
    // best-effort — a store write failure must never break the
    // foreground action/request that produced the value being stored
  }
}

// ---- profile ----

export async function getLocalProfile(): Promise<UserProfile | null> {
  return readJson<UserProfile | null>(KEY_PROFILE, null);
}

export async function setLocalProfile(profile: UserProfile): Promise<void> {
  await writeJson(KEY_PROFILE, profile);
}

// ---- habits ----

export async function getLocalHabits(): Promise<Habit[]> {
  return readJson<Habit[]>(KEY_HABITS, []);
}

export async function setLocalHabits(habits: Habit[]): Promise<void> {
  await writeJson(KEY_HABITS, habits);
}

export async function getLocalHabit(habitId: number): Promise<Habit | null> {
  const habits = await getLocalHabits();
  return habits.find((h) => h.id === habitId) ?? null;
}

export async function upsertLocalHabit(habit: Habit): Promise<void> {
  const habits = await getLocalHabits();
  const idx = habits.findIndex((h) => h.id === habit.id);
  if (idx >= 0) habits[idx] = habit;
  else habits.push(habit);
  await setLocalHabits(habits);
}

// ---- habit days ----

export async function getLocalHabitDays(habitId: number): Promise<HabitDay[]> {
  return readJson<HabitDay[]>(habitDaysKey(habitId), []);
}

export async function setLocalHabitDays(habitId: number, days: HabitDay[]): Promise<void> {
  await writeJson(habitDaysKey(habitId), days);
}

/**
 * Applies a done/snoozed/missed action to whichever day is currently
 * pending, using the exact same field-level rules as
 * JourneyService.recordAction() on the backend (status/action/
 * feedback_reason/feedback_note/completed_at, snooze_count only bumped for
 * "snoozed"). Returns the updated day list. Used both by api.ts's offline
 * action path and by drainNativeEvents() below, so a snooze/miss that
 * happened via the notification action is recorded identically to one
 * recorded via the in-app picker.
 */
export async function applyLocalAction(
  habitId: number,
  action: 'done' | 'snoozed' | 'missed',
  feedbackReason: FeedbackReason | null = null,
  feedbackNote: string | null = null
): Promise<{ days: HabitDay[]; dayNumber: number }> {
  const days = await getLocalHabitDays(habitId);
  const dayNumber = currentDayNumber(days);
  const idx = days.findIndex((d) => d.day_number === dayNumber);
  if (idx < 0) return { days, dayNumber };

  const day = days[idx];
  const status = action === 'done' ? 'done' : action === 'missed' ? 'missed' : 'pending';
  const updated: HabitDay = {
    ...day,
    status,
    action,
    feedback_reason: feedbackReason,
    feedback_note: feedbackNote,
    completed_at: action === 'done' ? new Date().toISOString() : day.completed_at,
    snooze_count: action === 'snoozed' ? day.snooze_count + 1 : day.snooze_count,
  };
  days[idx] = updated;
  await setLocalHabitDays(habitId, days);

  if (action === 'snoozed' && Platform.OS === 'android' && AlarmModule?.syncSnoozeCount) {
    await AlarmModule.syncSnoozeCount(habitId, updated.snooze_count).catch(() => {});
  }

  return { days, dayNumber };
}

export async function updateLocalHabitTime(habitId: number, timeOfDay: string): Promise<Habit | null> {
  const habit = await getLocalHabit(habitId);
  if (!habit) return null;
  const updated = { ...habit, time_of_day: timeOfDay };
  await upsertLocalHabit(updated);
  return updated;
}

// ---- pending sync queue ----

export type PendingSyncOp =
  | { id: string; type: 'action'; habitId: number; action: 'done' | 'snoozed' | 'missed'; feedbackReason: FeedbackReason | null; createdAt: number }
  | { id: string; type: 'schedule'; habitId: number; timeOfDay: string; createdAt: number }
  | { id: string; type: 'profile'; name: string; age: number; gender: Gender; createdAt: number };

/** Plain Omit<PendingSyncOp, ...> doesn't distribute over the union (it
 * collapses to only the fields common to every variant) — this does. */
type DistributiveOmit<T, K extends keyof any> = T extends unknown ? Omit<T, K> : never;
export type PendingSyncInput = DistributiveOmit<PendingSyncOp, 'id' | 'createdAt'>;

export async function listPendingSync(): Promise<PendingSyncOp[]> {
  return readJson<PendingSyncOp[]>(KEY_PENDING_SYNC, []);
}

export async function enqueuePendingSync(op: PendingSyncInput): Promise<void> {
  const queue = await listPendingSync();
  const withId = { ...op, id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, createdAt: Date.now() } as PendingSyncOp;
  queue.push(withId);
  await writeJson(KEY_PENDING_SYNC, queue);
}

export async function removePendingSync(id: string): Promise<void> {
  const queue = await listPendingSync();
  await writeJson(KEY_PENDING_SYNC, queue.filter((op) => op.id !== id));
}

// ---- native event drain ----

interface NativeEvent {
  type: 'snoozed' | 'missed';
  habitId: number;
  at: number;
}

/**
 * Pulls whatever AlarmActionReceiver recorded natively (see
 * NativeEventLog.kt) while JS wasn't running, applies each event to local
 * state via applyLocalAction, and queues it for backend sync. Call on
 * every app foreground/habit load (see App.tsx, HabitScreen.tsx) — cheap
 * no-op when nothing is queued.
 */
export async function drainNativeEvents(): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule?.drainNativeEvents) return;
  let raw: string;
  try {
    raw = await AlarmModule.drainNativeEvents();
  } catch {
    return;
  }
  let events: NativeEvent[];
  try {
    events = JSON.parse(raw ?? '[]');
  } catch {
    return;
  }
  for (const event of events) {
    await applyLocalAction(event.habitId, event.type, null, null);
    await enqueuePendingSync({ type: 'action', habitId: event.habitId, action: event.type, feedbackReason: null });
  }
}
