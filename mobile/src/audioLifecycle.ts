/**
 * Silences whatever the alarm is currently doing — used by STOP ALARM, and
 * by Done/Snooze/Missed acting as an implicit ack.
 *
 * The actual ringing (beep, alternating with spoken motivation) is now
 * entirely native — AlarmRingService, a foreground Service started
 * directly by AlarmReceiver the instant the alarm fires, using Android's
 * own TextToSpeech engine for the voice half (see AlarmRingService's
 * javadoc for why: the JS-side one-shot chime+speech this file used to
 * drive only ever ran if MainActivity reached the foreground and the JS
 * bridge was alive, which in practice was not reliable — the beep kept
 * working but the voice half silently never played). stopRingingAlarm()
 * below tears down both the beep and any in-flight native speech together.
 *
 * stopSpeaking() here is a separate, unrelated JS-side mechanism
 * (speech.ts) used for narrating a POST-ACTION response (see
 * HabitScreen.tsx's act()) — stopped here too since acting on the alarm is
 * an implicit ack of anything still being read out from a previous moment.
 */
import { stopSpeaking } from './speech';
import { stopRingingAlarm } from './notifications';

export function stopAlarmSequence(): void {
  stopSpeaking();
  stopRingingAlarm();
}
