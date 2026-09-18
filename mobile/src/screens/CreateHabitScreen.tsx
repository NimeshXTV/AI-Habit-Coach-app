import React, { useState } from 'react';
import { Alert, StyleSheet, Text, TextInput, View } from 'react-native';
import { api, ParsedGoal } from '../api';
import { Blob, ContourLines } from '../components/decor';
import ScreenSurface from '../components/ScreenSurface';
import { GhostButton, PrimaryButton } from '../components/Button';
import TimePickerModal from '../components/TimePickerModal';
import { colors, radii, spacing, type } from '../theme';
import type { Habit } from '../types';

interface Props {
  onCreated: (habit: Habit) => void;
  /** Only passed when reached from My Challenges (i.e. other challenges
   * already exist) — lets the user back out instead of being stuck here. */
  onCancel?: () => void;
}

export default function CreateHabitScreen({ onCreated, onCancel }: Props) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [askingTime, setAskingTime] = useState<ParsedGoal | null>(null);

  // Creating a challenge genuinely needs the backend (AI goal parsing) —
  // there is no local fallback for that, unlike viewing existing
  // challenges/progress. A failure here (e.g. Spring Boot unreachable)
  // should surface a clear message rather than an unhandled rejection.
  function reportCreateOffline() {
    Alert.alert("Couldn't reach the server", 'Creating a new challenge needs a connection to the backend. Check your connection and try again.');
  }

  async function handleStart() {
    const trimmed = text.trim();
    if (!trimmed) return;
    setBusy(true);
    try {
      const parsed = await api.parseGoal(trimmed);
      if (parsed.time_specified) {
        const habit = await api.createHabit(trimmed);
        onCreated(habit);
      } else {
        setAskingTime(parsed);
      }
    } catch {
      reportCreateOffline();
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirmTime(timeOfDay24h: string) {
    if (!askingTime) return;
    setBusy(true);
    try {
      const habit = await api.confirmHabit(text.trim(), timeOfDay24h);
      onCreated(habit);
    } catch {
      reportCreateOffline();
    } finally {
      setBusy(false);
    }
  }

  const decorations = (
    <>
      <Blob size={130} color={colors.accentGold} rotation={-8} style={styles.blob} />
      <ContourLines style={styles.contours} />
    </>
  );

  return (
    <ScreenSurface decorations={decorations}>
      <Text style={type.h1}>AI Habit Coach</Text>
      <Text style={styles.subtitle}>a 21-day adaptive journey</Text>
      <Text style={styles.label}>Tell it what you want to build, in your own words</Text>
      <TextInput
        style={styles.input}
        multiline
        placeholder="I want to go to the gym every day at 6pm for the next 21 days"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
      />
      <PrimaryButton label="Start" onPress={handleStart} disabled={!text.trim()} loading={busy} style={styles.primaryButtonSpacing} />
      {onCancel && <GhostButton label="Cancel" onPress={onCancel} />}

      <TimePickerModal
        visible={!!askingTime}
        initialTime={askingTime?.time_of_day ?? '08:00'}
        subtitle={askingTime ? `for ${askingTime.name.toLowerCase()}` : undefined}
        hint="We couldn't find a specific time in what you typed — this is what the alarm and voice coaching will use."
        busy={busy}
        onCancel={() => setAskingTime(null)}
        onConfirm={handleConfirmTime}
      />
    </ScreenSurface>
  );
}

const styles = StyleSheet.create({
  blob: { position: 'absolute', top: -50, right: -50, opacity: 0.85 },
  contours: { top: 0, left: -40, right: 0, bottom: 0 },
  subtitle: { ...type.small, marginBottom: spacing.xl, marginTop: spacing.xs },
  label: { ...type.small, marginBottom: spacing.md },
  input: {
    backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.md,
    color: colors.textPrimary, padding: 16, fontSize: 15.5, fontWeight: '500', minHeight: 100, textAlignVertical: 'top',
  },
  primaryButtonSpacing: { marginTop: spacing.lg, marginBottom: spacing.xs },
});
