import type { ActionResult, CurrentIntervention, FeedbackReason, Gender, Habit, HabitDay, UserProfile } from './types';
import { getOrCreateDeviceId } from './deviceId';
import { getApiBase, runBackendDiscovery } from './apiConfig';
import {
  applyLocalAction, enqueuePendingSync, getLocalHabit, getLocalHabitDays, getLocalHabits, getLocalProfile,
  listPendingSync, removePendingSync, setLocalHabitDays, setLocalHabits, setLocalProfile,
  updateLocalHabitTime, upsertLocalHabit,
} from './localStore';
import type { PendingSyncOp } from './localStore';
import {
  buildHistory, chooseResponseKind, chooseStrategy, currentDayNumber, renderActionResponse, renderIntervention,
  renderSummary, suggestAlternateTime,
} from './offlineCoach';

export { getApiBase } from './apiConfig';

/** Spring Boot is an OPTIONAL online service, never a prerequisite for the
 * app to open (see CLAUDE_CONTEXT.md's networking fix) — a `fetch()` with
 * no timeout can hang far longer than any UI should ever wait (tens of
 * seconds, sometimes indefinitely, when a host is simply unroutable, e.g.
 * a stale LAN IP after the phone switches to a mobile hotspot on a
 * different subnet). Every request aborts itself after this long instead,
 * so failures surface fast and calling code can fall back to cached data
 * or a clear "you're offline" state rather than hanging the whole screen. */
const REQUEST_TIMEOUT_MS = 5000;

/** Habit Advisor messages are the one request kind that isn't a quick local-DB
 * round trip — see AdvisorService/StrandsAdvisorProvider — they go all the way
 * to a real, reasoning-capable LLM (Bedrock Mantle / GPT-OSS 120B) and back.
 * That can easily take longer than REQUEST_TIMEOUT_MS's 5s (sized for "is the
 * backend even reachable", not "wait for an LLM"), which was aborting the
 * request client-side while the backend/Strands call was still genuinely in
 * flight and would have succeeded — surfacing as "Couldn't reach the Habit
 * Advisor" even though the backend was reachable and eventually responded. */
const ADVISOR_REQUEST_TIMEOUT_MS = 45000;

async function timedFetch(url: string, options?: RequestInit, timeoutMs: number = REQUEST_TIMEOUT_MS): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (e) {
    // The currently-used host just failed — most likely because the
    // network changed (new Wi-Fi, switched to/from a hotspot) and the
    // laptop's IP moved. Kick off LAN re-discovery in the background so
    // the *next* request has a shot at the right host; this request's own
    // failure/cache-fallback behavior below is unaffected either way.
    void runBackendDiscovery();
    if (e instanceof Error && e.name === 'AbortError') {
      throw new Error(`request to ${url} timed out after ${timeoutMs}ms (is the backend reachable?)`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

/** Every request is scoped to this installation's anonymous device id (see
 * deviceId.ts) — this is what keeps one device/tester from ever seeing
 * another's profile or habits, without requiring real authentication. */
async function deviceHeaders(): Promise<Record<string, string>> {
  const deviceId = await getOrCreateDeviceId();
  return { 'Content-Type': 'application/json', 'X-Device-Id': deviceId };
}

async function req<T>(path: string, options?: RequestInit, timeoutMs?: number): Promise<T> {
  const base = await getApiBase();
  const res = await timedFetch(`${base}${path}`, {
    headers: await deviceHeaders(),
    ...options,
  }, timeoutMs);
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${options?.method || 'GET'} ${path} -> ${res.status}: ${body}`);
  }
  // Any successful round-trip proves we're online right now — an
  // opportunistic moment to drain whatever offline actions are queued
  // (see localStore.ts/runPendingSync below). Fire-and-forget, deduped
  // internally, never delays this request's own result.
  void runPendingSync();
  return res.json();
}

export interface ParsedGoal {
  name: string;
  emoji: string;
  time_of_day: string;
  duration_minutes: number;
  total_days: number;
  time_specified: boolean;
}

export interface AdvisorHistoryTurn {
  role: 'user' | 'advisor';
  text: string;
}

/** Matches backend AdvisorMessageResponse exactly (snake_case on the
 * wire — see the backend's JacksonConfig). `available: false` is the only
 * shape possible today (no AI provider is wired in yet — see
 * UnavailableAdvisorProvider); `available: true` + `reply_text` is the
 * shape a real provider will return later, with no contract change. */
export interface AdvisorMessageResult {
  available: boolean;
  reply_text?: string;
  unavailable_reason?: string;
}

/** Network-only replay of a queued action/schedule change — used by
 * runPendingSync(), never called directly by screens. Bypasses the
 * local-first branching in api.act/api.updateSchedule below so replaying
 * a queued op can't itself re-queue a duplicate. */
async function replayPendingOp(op: PendingSyncOp): Promise<void> {
  if (op.type === 'action') {
    const result = await req<ActionResult>(`/habits/${op.habitId}/action`, {
      method: 'POST',
      body: JSON.stringify({ action: op.action, feedback_reason: op.feedbackReason ?? null }),
    });
    // The server is authoritative once reachable — refresh local days/habit
    // from it so anything only it knows (e.g. a Strands-generated response
    // that differed from our offline template) is reflected locally too.
    await api.getHabitDetail(op.habitId).catch(() => {});
    return void result;
  }
  if (op.type === 'schedule') {
    const updated = await req<Habit>(`/habits/${op.habitId}/schedule`, {
      method: 'POST',
      body: JSON.stringify({ time_of_day: op.timeOfDay }),
    });
    await upsertLocalHabit(updated);
    return;
  }
  // 'profile': replaces the locally-synthesized placeholder (see
  // api.saveProfile) with the server's real id/timestamps once reachable.
  const profile = await req<UserProfile>('/profile', {
    method: 'POST',
    body: JSON.stringify({ name: op.name, age: op.age, gender: op.gender }),
  });
  await setLocalProfile(profile);
}

/**
 * Replays queued offline actions/schedule changes against the backend, in
 * the order they happened, stopping at the first failure (still offline)
 * so ordering is never scrambled. Safe to call opportunistically and
 * often — an empty queue is a no-op. Call sites: after any successful
 * network request (see req() below) and on app startup (see App.tsx).
 */
let syncInFlight: Promise<void> | null = null;

export function runPendingSync(): Promise<void> {
  if (syncInFlight) return syncInFlight;
  syncInFlight = (async () => {
    try {
      const queue = await listPendingSync();
      for (const op of queue) {
        try {
          await replayPendingOp(op);
          await removePendingSync(op.id);
        } catch {
          return; // still offline (or this op keeps failing) — try again later
        }
      }
    } finally {
      syncInFlight = null;
    }
  })();
  return syncInFlight;
}

export const api = {
  parseGoal: (text: string) => req<ParsedGoal>('/habits/parse', { method: 'POST', body: JSON.stringify({ text }) }),

  createHabit: (text: string) => req<Habit>('/habits', { method: 'POST', body: JSON.stringify({ text }) }),

  confirmHabit: (text: string, time_of_day: string) =>
    req<Habit>('/habits/confirm', { method: 'POST', body: JSON.stringify({ text, time_of_day }) }),

  /** Reads always try the network first — the server is authoritative
   * whenever reachable. On failure, falls back to the on-device mirror
   * (see localStore.ts), which is kept up to date by every successful read
   * AND every locally-applied offline action, so "no fourth snooze"-style
   * accuracy holds even while offline. */
  listHabits: async (): Promise<Habit[]> => {
    try {
      const habits = await req<Habit[]>('/habits');
      await setLocalHabits(habits);
      return habits;
    } catch (networkError) {
      const local = await getLocalHabits();
      if (local.length) return local;
      throw networkError;
    }
  },

  getHabitDetail: async (habitId: number): Promise<{ habit: Habit; days: HabitDay[] }> => {
    try {
      const detail = await req<{ habit: Habit; days: HabitDay[] }>(`/habits/${habitId}`);
      await upsertLocalHabit(detail.habit);
      await setLocalHabitDays(habitId, detail.days);
      return detail;
    } catch (networkError) {
      const habit = await getLocalHabit(habitId);
      if (!habit) throw networkError;
      const days = await getLocalHabitDays(habitId);
      return { habit, days };
    }
  },

  /** Online: identical to before (server regenerates via Strands/template
   * every call). Offline: recomputed FRESH from local habit/days via
   * offlineCoach.ts on every call too — never just replaying a stale
   * cached string — using the exact same day/phase/strategy rules as the
   * backend (see offlineCoach.ts's javadoc). Never claims Strands: labeled
   * generated_by: 'offline'. */
  getCurrent: async (habitId: number): Promise<CurrentIntervention> => {
    try {
      return await req<CurrentIntervention>(`/habits/${habitId}/current`);
    } catch (networkError) {
      const habit = await getLocalHabit(habitId);
      if (!habit) throw networkError;
      const days = await getLocalHabitDays(habitId);
      const dayNumber = currentDayNumber(days);

      if (habit.status !== 'active') {
        const completed = days.filter((d) => d.status === 'done').length;
        return {
          habit, day_number: dayNumber, finished: true,
          summary: renderSummary(habit.name, completed, habit.total_days),
          summary_generated_by: 'offline',
        };
      }

      const history = buildHistory(dayNumber, days);
      const strategy = chooseStrategy(dayNumber, habit.total_days, history);
      const text = renderIntervention(habit.name, dayNumber, habit.total_days, habit.duration_minutes, habit.time_of_day, strategy, history);
      const suggestedTime = strategy === 'reschedule' ? suggestAlternateTime(habit.time_of_day) : undefined;

      return {
        habit, day_number: dayNumber, total_days: habit.total_days, finished: false,
        intervention_text: text, strategy, generated_by: 'offline', suggested_time: suggestedTime,
      };
    }
  },

  /**
   * Online: unchanged — the server records the action and returns the
   * authoritative response. Offline: applies the SAME state transition
   * locally (see localStore.applyLocalAction, a port of
   * JourneyService.recordAction) so the day/calendar/progress update
   * immediately, computes a deterministic local response for done/missed
   * (matching CoachingService.generateActionResponse's rules — never
   * called for snoozed, same as the backend), and queues the action for
   * backend sync. Always resolves — screens' existing try/catch around
   * api.act only fires for a genuinely unexpected error now, not for
   * "we're offline" (see HabitScreen.tsx's reportActionOffline, now a
   * true last resort).
   */
  act: async (habitId: number, action: 'done' | 'snoozed' | 'missed', feedback_reason?: FeedbackReason | null): Promise<ActionResult> => {
    try {
      return await req<ActionResult>(`/habits/${habitId}/action`, {
        method: 'POST',
        body: JSON.stringify({ action, feedback_reason: feedback_reason ?? null }),
      });
    } catch {
      const habit = await getLocalHabit(habitId);
      if (!habit) throw new Error(`No local data for habit ${habitId} — cannot record this action offline.`);

      const { days, dayNumber } = await applyLocalAction(habitId, action, feedback_reason ?? null, null);
      await enqueuePendingSync({ type: 'action', habitId, action, feedbackReason: feedback_reason ?? null });

      if (action === 'snoozed') {
        const snoozeCount = days.find((d) => d.day_number === dayNumber)?.snooze_count;
        return { ok: true, day_number: dayNumber, generated_by: 'offline', snooze_count: snoozeCount };
      }

      const completed = days.filter((d) => d.status === 'done').length;
      const { kind, consecutiveMissed } = chooseResponseKind(action, dayNumber, habit.total_days, days);
      const text = renderActionResponse(kind, habit.name, dayNumber, habit.total_days, completed);
      return {
        ok: true, day_number: dayNumber, response_text: text, response_kind: kind,
        generated_by: 'offline', consecutive_missed_days: consecutiveMissed,
      };
    }
  },

  /** Online: unchanged. Offline: updates the local habit's time_of_day
   * immediately (the native alarm is rescheduled by the caller regardless
   * — see HabitScreen.tsx's saveTime, unchanged) and queues the change for
   * backend sync. */
  updateSchedule: async (habitId: number, time_of_day: string): Promise<Habit> => {
    try {
      return await req<Habit>(`/habits/${habitId}/schedule`, { method: 'POST', body: JSON.stringify({ time_of_day }) });
    } catch {
      const updated = await updateLocalHabitTime(habitId, time_of_day);
      if (!updated) throw new Error(`No local data for habit ${habitId} — cannot change its time offline.`);
      await enqueuePendingSync({ type: 'schedule', habitId, timeOfDay: time_of_day });
      return updated;
    }
  },

  /**
   * Manual calendar-tap correction — sets a SPECIFIC day's status
   * directly, rather than acting on whichever day the backend considers
   * "current" (see api.act above). Deliberately NO offline fallback,
   * unlike api.act/api.updateSchedule: this edit is gated on
   * server-computed state (habit.status, the current day number) that can
   * legitimately drift between an offline tap and a later queued replay —
   * queuing it risks a silent, unreconcilable rejection long after the
   * user thought it worked. It fails fast; HabitScreen.tsx's existing
   * reportActionOffline() alert handles the failure the same way
   * updateSchedule's/the advisor's do.
   */
  editDayStatus: (habitId: number, dayNumber: number, status: 'pending' | 'done' | 'missed') =>
    req<{ ok: boolean; day_number: number; status: string }>(`/habits/${habitId}/days/${dayNumber}/status`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    }),

  continueHabit: (habitId: number) => req<Habit>(`/habits/${habitId}/continue`, { method: 'POST' }),

  stopHabit: (habitId: number) => req<{ ok: boolean }>(`/habits/${habitId}/stop`, { method: 'POST' }),

  deleteHabit: (habitId: number) => req<{ ok: boolean }>(`/habits/${habitId}`, { method: 'DELETE' }),

  /** Onboarding gate: null means either "no profile saved yet" (a 404 —
   * the expected shape of a fresh install/new device, see App.tsx) OR
   * "couldn't reach the backend AND nothing saved locally yet" — both
   * cases must let the app proceed rather than hang, so they're
   * deliberately not distinguished here. When a profile WAS previously
   * synced, a network failure returns that local copy instead (an
   * already-onboarded device must not get bounced back to onboarding just
   * because it's temporarily offline). */
  getProfile: async (): Promise<UserProfile | null> => {
    try {
      const base = await getApiBase();
      const res = await timedFetch(`${base}/profile`, { headers: await deviceHeaders() });
      // Any response at all (including the 404 below) proves the backend
      // is reachable right now — an opportunity to flush a profile/action/
      // schedule change that was queued while offline. Uses the same
      // dedup'd runPendingSync() req() triggers on success; this call site
      // needs its own trigger since it talks to fetch directly rather than
      // going through req() (its 404 handling isn't a plain "throw" case).
      void runPendingSync();
      if (res.status === 404) {
        // The backend genuinely has no profile for this device YET — but
        // that's also exactly what it looks like right after
        // saveProfile() created one locally while offline and hasn't
        // synced yet (see the pendingSync queue above). Trust a local
        // profile over a 404 so a device that's back online for the first
        // time since onboarding never gets bounced back to onboarding
        // while its own sync is still catching up.
        return getLocalProfile();
      }
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        throw new Error(`GET /profile -> ${res.status}: ${body}`);
      }
      const profile = await res.json();
      await setLocalProfile(profile);
      return profile;
    } catch (networkError) {
      return getLocalProfile();
    }
  },

  /**
   * Online: unchanged. Offline (including a device that has never once
   * been online — the very first launch): synthesizes a local-only profile
   * immediately so onboarding can complete and the app can proceed to My
   * Challenges, and queues the real creation for whenever the backend
   * becomes reachable (see replayPendingOp's 'profile' case, which
   * overwrites this placeholder with the server's real id/timestamps).
   * `id`/`created_at`/`updated_at` are placeholders — nothing in this app
   * reads them, they exist only because UserProfile's shape requires them.
   */
  saveProfile: async (name: string, age: number, gender: Gender): Promise<UserProfile> => {
    try {
      return await req<UserProfile>('/profile', { method: 'POST', body: JSON.stringify({ name, age, gender }) });
    } catch {
      const now = new Date().toISOString();
      const profile: UserProfile = { id: -1, name, age, gender, created_at: now, updated_at: now };
      await setLocalProfile(profile);
      await enqueuePendingSync({ type: 'profile', name, age, gender });
      return profile;
    }
  },

  /**
   * Habit Advisor — ONLINE-ONLY, deliberately with no offline fallback and
   * no local queueing (unlike every read/write above). A network failure
   * or timeout here must propagate to the caller as a thrown error, not be
   * silently absorbed into a cached/local/template response — see
   * AdvisorScreen.tsx, which is the only thing allowed to decide what
   * "unavailable" looks like on screen. A successful round-trip can still
   * carry `available: false` (the backend is reachable but no AI provider
   * is configured yet — see backend's UnavailableAdvisorProvider); that is
   * NOT an error, just the current honest answer.
   */
  sendAdvisorMessage: (habitId: number, message: string, history: AdvisorHistoryTurn[]): Promise<AdvisorMessageResult> =>
    req<AdvisorMessageResult>(`/habits/${habitId}/advisor/message`, {
      method: 'POST',
      body: JSON.stringify({ message, history }),
    }, ADVISOR_REQUEST_TIMEOUT_MS),
};
