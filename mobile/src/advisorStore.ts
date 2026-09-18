import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * On-device persistence for Habit Advisor conversations — one conversation
 * PER habitId, never shared or merged across habits (see AdvisorScreen.tsx:
 * every read/write here is keyed by the habit currently open, so a Gym
 * habit's conversation can never appear while looking at a Study habit's
 * advisor, and vice versa). Deliberately separate from localStore.ts's
 * offline-first habit/day/profile mirror — that store exists so core habit
 * functionality keeps working with zero network; this one exists purely so
 * reopening the same habit's advisor preserves what was already asked, and
 * carries NO offline-fallback meaning (see AdvisorScreen.tsx / api.ts: a
 * network failure is never answered from anything stored here — the Habit
 * Advisor is online-only).
 */

export type AdvisorRole = 'user' | 'advisor';

export interface AdvisorMessage {
  id: string;
  role: AdvisorRole;
  text: string;
  at: number;
}

/** Bounds how many past turns are kept on-device per habit — roughly 20
 * user/advisor exchanges. Independent of, but deliberately the same order
 * of magnitude as, the backend's own MAX_HISTORY_TURNS cap on what a
 * single request forwards to a provider (see AdvisorService.java). */
const MAX_STORED_MESSAGES = 40;

const conversationKey = (habitId: number) => `habit-coach:advisor:conversation:${habitId}`;

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
    // best-effort — a store write failure must never break the chat UI
  }
}

export async function getAdvisorConversation(habitId: number): Promise<AdvisorMessage[]> {
  return readJson<AdvisorMessage[]>(conversationKey(habitId), []);
}

/** Appends one message to THIS habit's conversation only, trims it back
 * down to MAX_STORED_MESSAGES from the front (oldest first) if needed, and
 * returns the resulting full list so the caller can render it immediately
 * without a second read. */
export async function appendAdvisorMessage(
  habitId: number,
  message: Omit<AdvisorMessage, 'id'>
): Promise<AdvisorMessage[]> {
  const existing = await getAdvisorConversation(habitId);
  const withNew = [...existing, { ...message, id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}` }];
  const bounded = withNew.length > MAX_STORED_MESSAGES
    ? withNew.slice(withNew.length - MAX_STORED_MESSAGES)
    : withNew;
  await writeJson(conversationKey(habitId), bounded);
  return bounded;
}

export async function clearAdvisorConversation(habitId: number): Promise<void> {
  try {
    await AsyncStorage.removeItem(conversationKey(habitId));
  } catch {
    // best-effort, same as writeJson above
  }
}

export { MAX_STORED_MESSAGES };
