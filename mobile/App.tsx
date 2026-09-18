import React, { useEffect, useState } from 'react';
import { StatusBar, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import AsyncStorage from '@react-native-async-storage/async-storage';
import CreateHabitScreen from './src/screens/CreateHabitScreen';
import HabitScreen from './src/screens/HabitScreen';
import ChallengesScreen from './src/screens/ChallengesScreen';
import OnboardingScreen from './src/screens/OnboardingScreen';
import { addAlarmLaunchListener, ensurePermissionsAndChannel, getInitialAlarm } from './src/notifications';
import { api, runPendingSync } from './src/api';
import { runBackendDiscovery } from './src/apiConfig';
import { drainNativeEvents } from './src/localStore';
import { colors } from './src/theme';
import type { Habit } from './src/types';

const STORAGE_KEY = 'habit-coach:activeHabitId';

type Screen = 'detail' | 'list' | 'create' | 'onboarding';

export default function App() {
  return (
    <SafeAreaProvider>
      <AppInner />
    </SafeAreaProvider>
  );
}

/** Each screen applies its own top/bottom safe-area inset (via
 * useSafeAreaInsets in ScreenSurface/HabitScreen) using ITS OWN background
 * color — a single root-level safe-area padding can't do that correctly
 * since the Habit Detail screen's warm-gold page background differs from
 * every other screen's peach one (see the statusBarBg note below); this
 * root stays a plain full-bleed container on purpose. */
function AppInner() {
  const [habitId, setHabitId] = useState<number | null>(null);
  // My Challenges is always the first screen the user actually chose to
  // land on — 'create' is reserved for "+ New Challenge" and for the
  // very first run when no challenge exists yet at all.
  const [screen, setScreen] = useState<Screen>('list');
  const [ready, setReady] = useState(false);
  const [openCoachSignal, setOpenCoachSignal] = useState(0);

  useEffect(() => {
    (async () => {
      // Spring Boot is an optional online service, never a prerequisite
      // for the app to open (see CLAUDE_CONTEXT.md's networking fix) —
      // every await below already fails fast and falls back to cached/
      // empty data on its own (see api.ts's timeout + cache fallback), but
      // this try/finally is a hard backstop: whatever happens inside,
      // `ready` must still flip to true so the app never gets stuck on a
      // blank/loading screen.
      try {
        // Fire-and-forget: try to find the backend on the LAN right away
        // (see apiConfig.ts/discovery.ts) so it's ready in time for the
        // very first request below on a network that's never been seen
        // before. Never awaited — must not delay app open (see the
        // networking-fix note below).
        void runBackendDiscovery();

        // Fold in any Snooze/Stop that happened via the notification
        // action while the app was fully closed (see AlarmActionReceiver.kt/
        // localStore.ts), then opportunistically flush anything queued
        // offline (no-ops instantly if there's nothing queued or we're
        // still offline — see api.ts's runPendingSync). Neither is awaited
        // here beyond the drain itself finishing (cheap, local-only) —
        // sync depends on the network and must not delay app open.
        await drainNativeEvents();
        void runPendingSync();

        await ensurePermissionsAndChannel();

        // First launch only: no profile saved yet -> onboarding gates
        // everything else (see CLAUDE_CONTEXT.md's onboarding flow). Note
        // this is also what a genuinely offline FIRST-EVER launch looks
        // like (nothing cached yet, backend unreachable) — onboarding is
        // the correct thing to show either way, since there is nothing
        // real to display without at least one successful sync.
        const profile = await api.getProfile();
        if (!profile) {
          setScreen('onboarding');
          return;
        }

        const stored = await AsyncStorage.getItem(STORAGE_KEY);
        const habits = await api.listHabits().catch(() => [] as Habit[]);
        if (stored && habits.some((h) => h.id === Number(stored))) {
          setHabitId(Number(stored));
        } else if (habits.length > 0) {
          setHabitId(habits[0].id);
        }
        // Every normal launch lands on My Challenges — even with zero
        // challenges, where ChallengesScreen shows its own empty state and
        // "+" is the only entry point into Create Challenge. `screen` is
        // already initialized to 'list' above; there is no exception here.

        // Cold start: the app was launched BY a habit alarm (either the
        // direct over-lock-screen launch, or tapping the shade notification
        // afterward) — it was not already running. This is what makes
        // "the alarm fires -> the coach opens" work even after the app was
        // fully closed, not just backgrounded. See src/notifications.ts.
        // Purely local (native module), so this always works regardless of
        // backend reachability.
        const launch = await getInitialAlarm();
        if (launch) {
          setHabitId(launch.habitId);
          setScreen('detail');
          setOpenCoachSignal(Date.now());
        }
      } finally {
        setReady(true);
      }
    })();

    // Warm launch: app already running (foreground or backgrounded-but-alive)
    // when a habit alarm fires. Always uses the id carried in the alarm's
    // own payload — never "whichever challenge was last open" — so habit
    // B's alarm opens B even if A was showing.
    const sub = addAlarmLaunchListener((payload) => {
      setHabitId(payload.habitId);
      setScreen('detail');
      setOpenCoachSignal(Date.now());
    });
    return () => sub.remove();
  }, []);

  function openChallenge(id: number) {
    setHabitId(id);
    AsyncStorage.setItem(STORAGE_KEY, String(id));
    setScreen('detail');
  }

  function handleHabitChanged(habit: Habit) {
    setHabitId(habit.id);
    AsyncStorage.setItem(STORAGE_KEY, String(habit.id));
    setScreen('detail');
  }

  function handleActiveHabitIdChange(id: number | null) {
    setHabitId(id);
    if (id == null) {
      AsyncStorage.removeItem(STORAGE_KEY);
    } else {
      AsyncStorage.setItem(STORAGE_KEY, String(id));
    }
    // Deliberately does NOT change `screen` — the user is on the My
    // Challenges list managing things and should stay there.
  }

  if (!ready) return <View style={styles.root} />;

  // Android is edge-to-edge (see mobile/android/gradle.properties'
  // edgeToEdgeEnabled=true) — StatusBar's backgroundColor prop is a no-op
  // there; the status bar is transparent by OS design and whatever each
  // screen's own top-level container paints shows through it. Each screen
  // (ScreenSurface-based screens, HabitScreen) pads its content below
  // useSafeAreaInsets().top with ITS OWN background color already, so
  // there's no seam to manage here — only the icon color (barStyle) is
  // actually configurable.
  return (
    <View style={styles.root}>
      <StatusBar barStyle="dark-content" />
      {screen === 'onboarding' && (
        <OnboardingScreen onDone={() => setScreen('list')} />
      )}
      {screen === 'list' && (
        <ChallengesScreen
          activeHabitId={habitId}
          onOpen={openChallenge}
          onCreateNew={() => setScreen('create')}
          onActiveHabitIdChange={handleActiveHabitIdChange}
        />
      )}
      {screen === 'create' && (
        <CreateHabitScreen
          onCreated={handleHabitChanged}
          onCancel={habitId != null ? () => setScreen('list') : undefined}
        />
      )}
      {screen === 'detail' && habitId != null && (
        <HabitScreen
          habitId={habitId}
          openCoachSignal={openCoachSignal}
          onHabitChanged={handleHabitChanged}
          onOpenChallenges={() => setScreen('list')}
          onSignalConsumed={() => setOpenCoachSignal(0)}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
});
