import { NativeModules } from 'react-native';

const { Discovery } = NativeModules;

/**
 * Thin bridge to DiscoveryModule.kt — sends a UDP broadcast probe and
 * waits for the Spring Boot backend to reply with its LAN IP (see
 * DiscoveryListener.java). Never throws: any failure (timeout, no native
 * module on this platform, etc.) resolves to null so callers can just
 * treat "not found" as one case, matching the rest of this app's
 * offline-tolerant style (see api.ts).
 */
export async function discoverBackendHost(timeoutMs = 1500): Promise<string | null> {
  if (!Discovery?.discoverHost) return null;
  try {
    const host = await Discovery.discoverHost(timeoutMs);
    return typeof host === 'string' && host.length > 0 ? host : null;
  } catch {
    return null;
  }
}
