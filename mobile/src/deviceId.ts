import AsyncStorage from '@react-native-async-storage/async-storage';

const DEVICE_ID_KEY = 'habit-coach:deviceId';

let cached: string | null = null;
let inFlight: Promise<string> | null = null;

function generateId(): string {
  const part = () => Math.random().toString(36).slice(2, 10);
  return `${Date.now().toString(36)}-${part()}-${part()}`;
}

/**
 * Anonymous per-installation identifier — NOT authentication. Generated
 * once on first use, persisted in AsyncStorage, and sent as the
 * X-Device-Id header on every backend request (see api.ts). This is what
 * scopes each device/tester to its own profile and habits server-side
 * (see backend's UserProfile/Habit deviceId columns): a fresh install has
 * never had this key written, so it always gets a brand-new id and — since
 * nothing on the backend is scoped to that id yet — a brand-new (empty)
 * profile/habit set, regardless of what other devices have created.
 */
export async function getOrCreateDeviceId(): Promise<string> {
  if (cached) return cached;
  if (inFlight) return inFlight;

  inFlight = (async () => {
    const existing = await AsyncStorage.getItem(DEVICE_ID_KEY);
    if (existing) {
      cached = existing;
      return existing;
    }
    const id = generateId();
    await AsyncStorage.setItem(DEVICE_ID_KEY, id);
    cached = id;
    return id;
  })();

  try {
    return await inFlight;
  } finally {
    inFlight = null;
  }
}
