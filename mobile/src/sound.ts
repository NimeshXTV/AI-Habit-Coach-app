/**
 * The alarm chime — a real, stoppable sound (not a fake decorative icon).
 * Uses the same asset file (assets/sounds/alarm.wav) that's bundled as the
 * native Android notification-channel sound, so the in-app chime and the
 * OS-level alarm sound are literally the same tone.
 *
 * This is always a single, bounded playthrough (~2.4s) — see
 * audioLifecycle.ts for how it's sequenced with speech so the two never
 * overlap.
 */
import { createAudioPlayer, AudioPlayer } from 'expo-audio';

let player: AudioPlayer | null = null;

export function playChime(): void {
  stopChime();
  player = createAudioPlayer(require('../assets/sounds/alarm.wav'));
  player.play();
}

export function stopChime(): void {
  if (player) {
    try {
      player.pause();
      player.remove();
    } catch (e) {
      // player may already be released — safe to ignore
    }
    player = null;
  }
}
