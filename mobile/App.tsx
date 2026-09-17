import React, { useEffect, useState } from 'react';
import { SafeAreaView, StatusBar, StyleSheet } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import CreateHabitScreen from './src/screens/CreateHabitScreen';
import HabitScreen from './src/screens/HabitScreen';
import ChallengesScreen from './src/screens/ChallengesScreen';
import OnboardingScreen from './src/screens/OnboardingScreen';
import { addAlarmLaunchListener, ensurePermissionsAndChannel, getInitialAlarm } from './src/notifications';
import { api } from './src/api';
import { colors } from './src/theme';
import type { Habit } from './src/types';

const STORAGE_KEY = 'habit-coach:activeHabitId';

type Screen = 'detail' | 'list' | 'create' | 'onboarding';

export default function App() {
  const [habitId, setHabitId] = useState<number | null>(null);
  // My Challenges is always the first screen the user actually chose to
  // land on — 'create' is reserved for "+ New Challenge" and for the
  // very first run when no challenge exists yet at all.
  const [screen, setScreen] = useState<Screen>('list');
  const [ready, setReady] = useState(false);
  const [openCoachSignal, setOpenCoachSignal] = useState(0);

  useEffect(() => {
    (async () => {
      await ensurePermissionsAndChannel();

      // First launch only: no profile saved yet -> onboarding gates
      // everything else (see CLAUDE_CONTEXT.md's onboarding flow). A fresh
      // install can't have any habits yet either (habit creation is only
      // reachable from My Challenges, which onboarding gates), so it's
      // safe to skip the rest of this effect (habit/alarm-launch loading)
      // entirely and let OnboardingScreen's onDone hand off to 'list'.
      const profile = await api.getProfile().catch(() => null);
      if (!profile) {
        setScreen('onboarding');
        setReady(true);
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
      setReady(true);

      // Cold start: the app was launched BY a habit alarm (either the
      // direct over-lock-screen launch, or tapping the shade notification
      // afterward) — it was not already running. This is what makes
      // "the alarm fires -> the coach opens" work even after the app was
      // fully closed, not just backgrounded. See src/notifications.ts.
      const launch = await getInitialAlarm();
      if (launch) {
        setHabitId(launch.habitId);
        setScreen('detail');
        setOpenCoachSignal(Date.now());
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

  if (!ready) return <SafeAreaView style={styles.root} />;

  // The Habit Detail/Coach screen uses its own warm-gold background (see
  // HabitScreen.tsx's `page` style) rather than the rest of the app's
  // peach — matching the status bar strip to it avoids a visible seam.
  const statusBarBg = screen === 'detail' ? colors.habitPageBg : colors.background;

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="dark-content" backgroundColor={statusBarBg} />
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
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
});
