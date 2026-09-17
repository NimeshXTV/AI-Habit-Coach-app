/**
 * The single audio-lifecycle entry point for "the coach has something to
 * say out loud" — used by a real scheduled-alarm tap, Test Coach, and the
 * post-snooze re-prompt. Guarantees only ONE JS audio source is ever
 * active: the chime plays once, fully stops, and only THEN does speech
 * start — never simultaneously. `stopAlarmSequence()` can interrupt either
 * stage (used by STOP ALARM, and by Done/Snooze/Missed acting as an
 * implicit ack).
 *
 * The bounded setTimeout below is pure UI-audio sequencing of two clips
 * that already fired in response to an event (a tap, a button press) — it
 * is NOT a polling mechanism and has nothing to do with how the alarm gets
 * scheduled or triggered (that's still 100% native AlarmManager, see
 * notifications.ts). Nothing here waits for or checks the clock.
 *
 * This is deliberately NOT what makes the alarm "keep ringing" — this
 * one-shot chime+speech is a JS-only narration layer that only runs while
 * the JS bridge happens to be alive (foreground). The actual continuous,
 * survives-background/lock/swipe ringing is AlarmRingService, a native
 * foreground Service started directly by AlarmReceiver the instant the
 * alarm fires — see its javadoc. stopAlarmSequence() below stops BOTH
 * layers together, since every user action (Done/Snooze/Missed/Stop) that
 * calls this must silence everything at once.
 */
import { playChime, stopChime } from './sound';
import { speakCoachMessage, stopSpeaking } from './speech';
import { stopRingingAlarm } from './notifications';

const CHIME_DURATION_MS = 2500; // assets/sounds/alarm.wav is ~2.4s

let pendingTimer: ReturnType<typeof setTimeout> | null = null;

export function playAlarmSequence(text: string): void {
  stopAlarmSequence();
  playChime();
  pendingTimer = setTimeout(() => {
    pendingTimer = null;
    speakCoachMessage(text);
  }, CHIME_DURATION_MS);
}

export function stopAlarmSequence(): void {
  if (pendingTimer) {
    clearTimeout(pendingTimer);
    pendingTimer = null;
  }
  stopChime();
  stopSpeaking();
  stopRingingAlarm(); // silence the native continuous ring too — see class javadoc
}
