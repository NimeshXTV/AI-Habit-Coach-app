import type { ActionResult, CurrentIntervention, FeedbackReason, Gender, Habit, HabitDay, UserProfile } from './types';
import { getOrCreateDeviceId } from './deviceId';
import { getApiBase, runBackendDiscovery } from './apiConfig';
import { cacheGet, cacheSet } from './localCache';

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

async function timedFetch(url: string, options?: RequestInit): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
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
      throw new Error(`request to ${url} timed out after ${REQUEST_TIMEOUT_MS}ms (is the backend reachable?)`);
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

async function req<T>(path: string, options?: RequestInit): Promise<T> {
  const base = await getApiBase();
  const res = await timedFetch(`${base}${path}`, {
    headers: await deviceHeaders(),
    ...options,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${options?.method || 'GET'} ${path} -> ${res.status}: ${body}`);
  }
  return res.json();
}

/**
 * A read endpoint that mirrors its last successful response into
 * localCache and falls back to that snapshot when the network call fails
 * for ANY reason (timeout, DNS/connect failure, non-2xx) — this is what
 * makes "My Challenges loads" / "Habit Detail loads" / "local progress
 * loads" true even with Spring Boot completely unreachable. Only rethrows
 * when there is no cached snapshot to fall back to (e.g. this exact data
 * was never successfully fetched on this device before), since at that
 * point there is genuinely nothing real to show.
 */
async function cachedReq<T>(cacheKey: string, path: string): Promise<T> {
  try {
    const data = await req<T>(path);
    await cacheSet(cacheKey, data);
    return data;
  } catch (networkError) {
    const cached = await cacheGet<T>(cacheKey);
    if (cached !== null) return cached;
    throw networkError;
  }
}

export interface ParsedGoal {
  name: string;
  emoji: string;
  time_of_day: string;
  duration_minutes: number;
  total_days: number;
  time_specified: boolean;
}

export const api = {
  parseGoal: (text: string) => req<ParsedGoal>('/habits/parse', { method: 'POST', body: JSON.stringify({ text }) }),

  createHabit: (text: string) => req<Habit>('/habits', { method: 'POST', body: JSON.stringify({ text }) }),

  confirmHabit: (text: string, time_of_day: string) =>
    req<Habit>('/habits/confirm', { method: 'POST', body: JSON.stringify({ text, time_of_day }) }),

  listHabits: () => cachedReq<Habit[]>('habits', '/habits'),

  getHabitDetail: (habitId: number) =>
    cachedReq<{ habit: Habit; days: HabitDay[] }>(`habitDetail:${habitId}`, `/habits/${habitId}`),

  getCurrent: (habitId: number) => cachedReq<CurrentIntervention>(`current:${habitId}`, `/habits/${habitId}/current`),

  act: (habitId: number, action: 'done' | 'snoozed' | 'missed', feedback_reason?: FeedbackReason | null) =>
    req<ActionResult>(`/habits/${habitId}/action`, {
      method: 'POST',
      body: JSON.stringify({ action, feedback_reason: feedback_reason ?? null }),
    }),

  updateSchedule: (habitId: number, time_of_day: string) =>
    req<Habit>(`/habits/${habitId}/schedule`, { method: 'POST', body: JSON.stringify({ time_of_day }) }),

  continueHabit: (habitId: number) => req<Habit>(`/habits/${habitId}/continue`, { method: 'POST' }),

  stopHabit: (habitId: number) => req<{ ok: boolean }>(`/habits/${habitId}/stop`, { method: 'POST' }),

  deleteHabit: (habitId: number) => req<{ ok: boolean }>(`/habits/${habitId}`, { method: 'DELETE' }),

  /** Onboarding gate: null means either "no profile saved yet" (a 404 —
   * the expected shape of a fresh install/new device, see App.tsx) OR
   * "couldn't reach the backend AND nothing cached yet" — both cases must
   * let the app proceed rather than hang, so they're deliberately not
   * distinguished here. When a profile WAS previously cached, a network
   * failure returns that cached profile instead (an already-onboarded
   * device must not get bounced back to onboarding just because it's
   * temporarily offline). */
  getProfile: async (): Promise<UserProfile | null> => {
    try {
      const base = await getApiBase();
      const res = await timedFetch(`${base}/profile`, { headers: await deviceHeaders() });
      if (res.status === 404) return null;
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        throw new Error(`GET /profile -> ${res.status}: ${body}`);
      }
      const profile = await res.json();
      await cacheSet('profile', profile);
      return profile;
    } catch (networkError) {
      const cached = await cacheGet<UserProfile>('profile');
      return cached;
    }
  },

  saveProfile: (name: string, age: number, gender: Gender) =>
    req<UserProfile>('/profile', { method: 'POST', body: JSON.stringify({ name, age, gender }) }),
};
