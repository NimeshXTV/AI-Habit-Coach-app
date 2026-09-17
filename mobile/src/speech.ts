/**
 * The single place that ever calls the platform TTS engine. Every screen in
 * this app that shows a coaching line calls THIS function with that exact
 * same string — never a re-derived or paraphrased copy — so displayed text
 * and spoken text can never drift apart.
 */
import * as Speech from 'expo-speech';

export function speakCoachMessage(text: string): void {
  if (!text) return;
  Speech.stop();
  Speech.speak(text, { pitch: 1.0, rate: 1.0 });
}

export function stopSpeaking(): void {
  Speech.stop();
}
