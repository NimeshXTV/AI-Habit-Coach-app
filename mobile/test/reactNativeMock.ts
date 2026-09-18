/**
 * Minimal stand-in for the 'react-native' package under plain Jest (no RN
 * runtime) — see jest.config.js's moduleNameMapper. api.ts transitively
 * imports localStore.ts, which imports NativeModules/Platform from
 * react-native for its native alarm-event-draining code path; that code is
 * never exercised by the advisor tests, but the module still needs to
 * load. Real app code never imports this file.
 */
export const NativeModules: Record<string, unknown> = {};

export const Platform = {
  OS: 'android' as const,
  select: <T>(obj: { android?: T; default?: T }): T | undefined => obj.android ?? obj.default,
};
