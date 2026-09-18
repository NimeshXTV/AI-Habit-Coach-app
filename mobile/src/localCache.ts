import AsyncStorage from '@react-native-async-storage/async-storage';

const PREFIX = 'habit-coach:cache:';

/**
 * Last-known-good snapshots of GET responses, so the app has something real
 * to show when Spring Boot is unreachable (see api.ts) instead of an
 * infinite spinner or a crash. Every write here is a mirror of an actual
 * server response — nothing is fabricated locally. Deliberately NOT a
 * general offline write queue: actions that mutate state (done/missed/
 * snooze/schedule/...) still require connectivity and fail fast with a
 * clear error when offline (see api.ts's `req`), rather than being queued
 * for later sync — that is a materially bigger feature than "don't hang on
 * startup" and wasn't asked for here.
 */
export async function cacheGet<T>(key: string): Promise<T | null> {
  try {
    const raw = await AsyncStorage.getItem(PREFIX + key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export async function cacheSet<T>(key: string, value: T): Promise<void> {
  try {
    await AsyncStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Best-effort only — a cache write failure must never break the
    // foreground request that produced the value being cached.
  }
}

export async function cacheRemove(key: string): Promise<void> {
  try {
    await AsyncStorage.removeItem(PREFIX + key);
  } catch {
    // best-effort
  }
}
