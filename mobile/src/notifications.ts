/**
 * OS-delegated habit alarms.
 *
 * IMPORTANT ARCHITECTURE NOTE: scheduling itself was always native
 * (AlarmManager, via expo-notifications' trigger) — that part worked fine.
 * What did NOT work is that a plain scheduled *notification* only ever
 * opens the app on TAP; posting it while the app is backgrounded or the
 * phone is locked does nothing more (no sound beyond the one heads-up
 * chime, no TTS, no ringing UI) until the user manually opens the app.
 * expo-notifications (57.0.19) has no full-screen-intent / alarm-clock
 * support to fix that from JS alone.
 *
 * So the daily/snooze alarms are now scheduled through a small native
 * module (AlarmModule, see android/app/src/main/java/.../AlarmModule.kt)
 * that uses AlarmManager.setAlarmClock() — the one API whose firing
 * receiver is exempted from Android's background-activity-launch
 * restrictions, letting it start MainActivity directly, over the lock
 * screen, with zero taps.
 *
 * The alarm's actual sound is likewise fully native now — AlarmReceiver
 * starts AlarmRingService, a foreground Service that loops the alarm sound
 * continuously (not a single chime) until stopRingingAlarm() below is
 * called, and survives the app being backgrounded, swiped from Recents, or
 * the phone being locked, none of which a JS timer/audio player could (see
 * AlarmRingService's javadoc). It owns its own notification (channel
 * `habit-alarm-ringing`, created natively) — this file's CHANNEL_ID/
 * ensurePermissionsAndChannel() below now exist purely to request the
 * POST_NOTIFICATIONS permission ahead of time; nothing posts into that
 * specific channel anymore.
 *
 * Two independent identifiers per habit, exactly as before:
 *   - `daily`  — the permanent, repeating alarm at the habit's saved
 *     time_of_day. The native side re-arms tomorrow's occurrence itself
 *     the moment today's fires (see AlarmReceiver), so the daily alarm
 *     keeps going even if the user never opens the app to act on today's.
 *   - `snooze` — a one-off reminder a few minutes out, chosen explicitly
 *     by the user (5/10/15 min). Never touches the daily alarm or the
 *     habit's stored time.
 */
import { DeviceEventEmitter, EmitterSubscription, NativeModules, Platform } from 'react-native';
import * as Notifications from 'expo-notifications';
import type { Habit } from './types';

const { AlarmModule } = NativeModules;

// Bumped to -v2: Android notification channels are immutable once created on
// a device — changing an existing channel's `sound` programmatically does
// nothing for users who already have the old channel. A new channel id is
// the only reliable way to ship a changed sound.
const CHANNEL_ID = 'habit-alarms-v2';
// Matches assets/sounds/alarm.wav, copied to android/app/src/main/res/raw/alarm.wav
// (native resource name, no extension) — this is what makes the alarm sound
// play at the OS level the instant the notification posts.
const ALARM_SOUND = 'alarm';
export const NOTIFICATION_DATA_KIND = { DAILY: 'daily', SNOOZE: 'snooze' } as const;

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/** Still needed purely to request POST_NOTIFICATIONS (Android 13+) ahead of
 * time — see the file-level note above on why the channel this creates is
 * no longer actually posted into. */
export async function ensurePermissionsAndChannel(): Promise<boolean> {
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync(CHANNEL_ID, {
      name: 'Habit alarms',
      importance: Notifications.AndroidImportance.MAX,
      sound: ALARM_SOUND,
      vibrationPattern: [0, 250, 250, 250],
      lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    });
  }
  const existing = await Notifications.getPermissionsAsync();
  if (existing.granted) return true;
  const requested = await Notifications.requestPermissionsAsync({
    ios: { allowAlert: true, allowSound: true, allowBadge: false },
  });
  return requested.granted;
}

function nextOccurrenceMillis(timeOfDay: string): number {
  const [hour, minute] = timeOfDay.split(':').map(Number);
  const next = new Date();
  next.setSeconds(0, 0);
  next.setHours(hour, minute);
  if (next.getTime() <= Date.now()) {
    next.setDate(next.getDate() + 1);
  }
  return next.getTime();
}

export async function scheduleDailyAlarm(habit: Habit): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule) return;
  await AlarmModule.scheduleAlarm(
    habit.id,
    NOTIFICATION_DATA_KIND.DAILY,
    nextOccurrenceMillis(habit.time_of_day),
    habit.name,
    habit.emoji,
    habit.time_of_day
  );
}

export async function cancelDailyAlarm(habitId: number): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule) return;
  await AlarmModule.cancelAlarm(habitId, NOTIFICATION_DATA_KIND.DAILY).catch(() => {});
}

/**
 * A temporary re-prompt `minutesFromNow` out — does NOT modify the
 * permanent daily alarm or the habit's stored time_of_day. Re-snoozing
 * replaces the previous pending snooze (same identifier) rather than
 * stacking multiple.
 *
 * Timing is anchored to Date.now() AT THE MOMENT THIS RUNS — i.e. the
 * instant the caller confirms the snooze choice — never to the original
 * alarm's trigger time or the habit's daily time_of_day. Callers must
 * invoke this immediately on confirmation (before any other awaited work)
 * so the anchor isn't skewed by unrelated network latency.
 */
export async function scheduleSnoozeAlarm(habit: Habit, minutesFromNow: number): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule) return;
  await AlarmModule.scheduleAlarm(
    habit.id,
    NOTIFICATION_DATA_KIND.SNOOZE,
    Date.now() + minutesFromNow * 60_000,
    habit.name,
    habit.emoji,
    habit.time_of_day
  );
}

export async function cancelSnoozeAlarm(habitId: number): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule) return;
  await AlarmModule.cancelAlarm(habitId, NOTIFICATION_DATA_KIND.SNOOZE).catch(() => {});
}

/**
 * Stops the continuously-ringing native alarm (see AlarmRingService) —
 * called from audioLifecycle.stopAlarmSequence(), which is itself already
 * called at every point the user acts on a ringing alarm (Done, opening the
 * Snooze/Missed pickers, STOP ALARM), so this needs no separate wiring per
 * action. Safe to call even when nothing is ringing.
 */
export async function stopRingingAlarm(): Promise<void> {
  if (Platform.OS !== 'android' || !AlarmModule) return;
  await AlarmModule.stopRinging().catch(() => {});
}

/**
 * Clears any currently-PRESENTED alarm notification (the one sitting in the
 * shade/lock screen) for this habit. Used by STOP ALARM — this only
 * dismisses the alert that already fired; it never touches scheduled
 * future triggers (the daily alarm and any pending snooze keep running
 * independently).
 */
export async function dismissPresentedAlarmNotifications(habitId?: number): Promise<void> {
  await Notifications.dismissAllNotificationsAsync().catch(() => {});
  if (Platform.OS === 'android' && AlarmModule && habitId != null) {
    await AlarmModule.dismissAlarmNotification(habitId, NOTIFICATION_DATA_KIND.DAILY).catch(() => {});
    await AlarmModule.dismissAlarmNotification(habitId, NOTIFICATION_DATA_KIND.SNOOZE).catch(() => {});
  }
}

export interface AlarmLaunch {
  habitId: number;
  kind: 'daily' | 'snooze';
}

/** Cold start: was this launch of the app caused by a habit alarm going
 * off (either the direct over-lock-screen launch, or the user tapping the
 * shade notification afterward)? Consumed exactly once on the native side. */
export async function getInitialAlarm(): Promise<AlarmLaunch | null> {
  if (Platform.OS !== 'android' || !AlarmModule) return null;
  return AlarmModule.getInitialAlarm();
}

/** Warm case: app already running (foreground or backgrounded-but-alive)
 * when a habit alarm fires. Pushed from native — no JS polling involved. */
export function addAlarmLaunchListener(handler: (payload: AlarmLaunch) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener('onHabitAlarm', handler);
}
