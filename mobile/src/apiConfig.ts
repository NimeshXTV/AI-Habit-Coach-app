import { Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { discoverBackendHost } from './discovery';

const API_HOST_KEY = 'habit-coach:apiHost';
const DISCOVERED_HOST_KEY = 'habit-coach:discoveredHost';
const API_PORT = 8899;

/**
 * The Android EMULATOR's own "localhost" is the emulator itself, not the
 * host machine running Spring Boot — 10.0.2.2 is the documented alias the
 * emulator provides for the host's loopback. This is a harmless, universal
 * default (every emulator has it); it is NOT a stand-in for a real
 * developer's LAN IP, which is why it's the only host value hardcoded here.
 *
 * A REAL PHYSICAL PHONE has no such alias — it must reach the dev machine
 * (or, later, a real server) over an actual address, and that address
 * changes with whatever network the phone happens to be on (home Wi-Fi,
 * a different Wi-Fi, a mobile hotspot, ...). Hardcoding any one of those
 * is exactly the bug this file exists to fix (see CLAUDE_CONTEXT.md's
 * networking investigation) — instead the host is a runtime-configurable
 * value, persisted in AsyncStorage, editable in-app via
 * components/ApiHostModal.tsx (My Challenges' ⚙ button) without rebuilding
 * the APK.
 */
const EMULATOR_DEFAULT_HOST = Platform.OS === 'android' ? '10.0.2.2' : 'localhost';

let cachedManualHost: string | null | undefined; // undefined = not loaded yet, null = none set
let cachedDiscoveredHost: string | null = null;
let discoveryInFlight: Promise<void> | null = null;

async function loadManualHost(): Promise<string | null> {
  if (cachedManualHost !== undefined) return cachedManualHost;
  const stored = await AsyncStorage.getItem(API_HOST_KEY);
  cachedManualHost = stored && stored.trim() ? stored.trim() : null;
  return cachedManualHost;
}

/**
 * Resolution order: an explicit manual host (ApiHostModal's "Save") always
 * wins, since it's the user's own stated intent and must survive
 * whatever LAN discovery happens to find. Otherwise, the most recently
 * auto-discovered host (see runBackendDiscovery below) is used, falling
 * back to the previous session's discovered host from AsyncStorage, and
 * finally to the emulator-only default. This function itself never
 * triggers network activity — see App.tsx/api.ts for where discovery is
 * actually kicked off, so opening the app or making a request is never
 * blocked on it (see CLAUDE_CONTEXT.md's networking fix).
 */
export async function getApiHost(): Promise<string> {
  const manual = await loadManualHost();
  if (manual) return manual;
  if (cachedDiscoveredHost) return cachedDiscoveredHost;
  const stored = await AsyncStorage.getItem(DISCOVERED_HOST_KEY);
  if (stored && stored.trim()) {
    cachedDiscoveredHost = stored.trim();
    return cachedDiscoveredHost;
  }
  return EMULATOR_DEFAULT_HOST;
}

export async function setApiHost(host: string): Promise<void> {
  const trimmed = host.trim();
  cachedManualHost = trimmed || null;
  if (trimmed) {
    await AsyncStorage.setItem(API_HOST_KEY, trimmed);
  } else {
    await AsyncStorage.removeItem(API_HOST_KEY);
  }
}

/**
 * Attempts to find the backend automatically over the LAN (see
 * discovery.ts/DiscoveryModule.kt) and, if found, remembers it for
 * getApiHost() to use — but only when the user hasn't manually pinned a
 * host, so a manual override is never silently clobbered by discovery.
 * Safe to call speculatively/repeatedly (e.g. on app start, or whenever a
 * request fails): concurrent calls share one in-flight attempt, and any
 * failure is silent, leaving whatever host was already in use untouched.
 */
export function runBackendDiscovery(): Promise<void> {
  if (discoveryInFlight) return discoveryInFlight;
  discoveryInFlight = (async () => {
    try {
      const manual = await loadManualHost();
      if (manual) return;
      const found = await discoverBackendHost();
      if (found) {
        cachedDiscoveredHost = found;
        await AsyncStorage.setItem(DISCOVERED_HOST_KEY, found);
      }
    } finally {
      discoveryInFlight = null;
    }
  })();
  return discoveryInFlight;
}

/**
 * Local development talks to Spring Boot over plain HTTP on a LAN (see
 * AndroidManifest.xml's usesCleartextTraffic, scoped to this reason) — a
 * real production/AWS deployment would be HTTPS instead. Rather than
 * hardcoding a scheme, if whatever was entered already looks like a full
 * URL (contains "://"), it's used exactly as given (so a future
 * "https://api.example.com" production value works with zero code
 * changes); otherwise it's treated as a bare dev host and gets the
 * http://<host>:8899/api convenience form.
 */
export async function getApiBase(): Promise<string> {
  const host = await getApiHost();
  if (host.includes('://')) return host.replace(/\/+$/, '');
  return `http://${host}:${API_PORT}/api`;
}

export { EMULATOR_DEFAULT_HOST };
