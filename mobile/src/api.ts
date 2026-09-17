import { Platform } from 'react-native';
import type { ActionResult, CurrentIntervention, FeedbackReason, Gender, Habit, HabitDay, UserProfile } from './types';

// The Android EMULATOR's own "localhost" is the emulator itself, not the
// host machine running the FastAPI server — 10.0.2.2 is the documented
// alias the emulator provides for the host's loopback.
//
// A REAL PHYSICAL PHONE has no such alias: it must reach the dev machine
// over the actual network, so this constant has to be that machine's LAN
// IP (both devices on the same Wi-Fi). Find it with:
//   Linux/macOS: hostname -I   (or ip addr / ifconfig)
//   Windows:     ipconfig
// then set PHYSICAL_DEVICE_HOST below and flip USE_PHYSICAL_DEVICE_HOST to
// true before building for a physical device.
const USE_PHYSICAL_DEVICE_HOST = true;
const PHYSICAL_DEVICE_HOST = '192.168.1.18'; // <-- replace with YOUR machine's LAN IP

const HOST = USE_PHYSICAL_DEVICE_HOST
  ? PHYSICAL_DEVICE_HOST
  : Platform.OS === 'android' ? '10.0.2.2' : 'localhost';
export const API_BASE = `http://${HOST}:8899/api`;

async function req<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${options?.method || 'GET'} ${path} -> ${res.status}: ${body}`);
  }
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

export const api = {
  parseGoal: (text: string) => req<ParsedGoal>('/habits/parse', { method: 'POST', body: JSON.stringify({ text }) }),

  createHabit: (text: string) => req<Habit>('/habits', { method: 'POST', body: JSON.stringify({ text }) }),

  confirmHabit: (text: string, time_of_day: string) =>
    req<Habit>('/habits/confirm', { method: 'POST', body: JSON.stringify({ text, time_of_day }) }),

  listHabits: () => req<Habit[]>('/habits'),

  getHabitDetail: (habitId: number) => req<{ habit: Habit; days: HabitDay[] }>(`/habits/${habitId}`),

  getCurrent: (habitId: number) => req<CurrentIntervention>(`/habits/${habitId}/current`),

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

  /** Onboarding gate: null (not a thrown error) means "no profile saved
   * yet" — a 404 is the expected, normal shape of a fresh install, not a
   * failure — see App.tsx, which uses this to decide whether to show
   * OnboardingScreen. Any other non-2xx still throws, same as `req`. */
  getProfile: async (): Promise<UserProfile | null> => {
    const res = await fetch(`${API_BASE}/profile`);
    if (res.status === 404) return null;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      throw new Error(`GET /profile -> ${res.status}: ${body}`);
    }
    return res.json();
  },

  saveProfile: (name: string, age: number, gender: Gender) =>
    req<UserProfile>('/profile', { method: 'POST', body: JSON.stringify({ name, age, gender }) }),
};
