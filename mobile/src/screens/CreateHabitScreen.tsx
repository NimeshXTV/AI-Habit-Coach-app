import React, { useState } from 'react';
import { Platform, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import DateTimePicker from '@react-native-community/datetimepicker';
import { api, ParsedGoal } from '../api';
import { fmtTime, to24h, parseTimeToDate } from '../format';
import { Blob, ContourLines } from '../components/decor';
import ScreenSurface from '../components/ScreenSurface';
import { GhostButton, PrimaryButton } from '../components/Button';
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
  const [pickedTime, setPickedTime] = useState<Date>(new Date());
  const [showPicker, setShowPicker] = useState(Platform.OS === 'ios');

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
        setPickedTime(parseTimeToDate(parsed.time_of_day));
        setAskingTime(parsed);
      }
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirmTime() {
    if (!askingTime) return;
    setBusy(true);
    try {
      const habit = await api.confirmHabit(text.trim(), to24h(pickedTime));
      onCreated(habit);
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

  if (askingTime) {
    return (
      <ScreenSurface decorations={decorations}>
        <Text style={styles.timeTitle}>When should we{'\n'}remind you?</Text>
        <Text style={styles.timeSubtitle}>for {askingTime.name.toLowerCase()}</Text>
        <View style={styles.timeCard}>
          <Text style={styles.bigTime}>{fmtTime(to24h(pickedTime))}</Text>
          {Platform.OS === 'android' && !showPicker && (
            <TouchableOpacity style={styles.timeButton} onPress={() => setShowPicker(true)}>
              <Text style={styles.timeButtonText}>Choose time</Text>
            </TouchableOpacity>
          )}
          {showPicker && (
            <DateTimePicker
              value={pickedTime}
              mode="time"
              is24Hour={false}
              display={Platform.OS === 'ios' ? 'spinner' : 'default'}
              onChange={(_event, date) => {
                if (Platform.OS === 'android') setShowPicker(false);
                if (date) setPickedTime(date);
              }}
            />
          )}
        </View>
        <Text style={styles.hint}>
          We couldn't find a specific time in what you typed — this is what the alarm and voice coaching will use.
        </Text>
        <PrimaryButton label="Confirm time" onPress={handleConfirmTime} loading={busy} style={styles.primaryButtonSpacing} />
        <GhostButton label="Cancel" onPress={() => setAskingTime(null)} />
      </ScreenSurface>
    );
  }

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
  hint: { ...type.small, fontSize: 12.5, marginTop: spacing.md, marginBottom: spacing.lg },
  timeTitle: { ...type.h1, fontSize: 24 },
  timeSubtitle: { ...type.small, marginTop: spacing.xs, marginBottom: spacing.lg },
  timeCard: { backgroundColor: colors.surfaceCard, borderRadius: radii.lg, padding: spacing.lg, alignItems: 'center' },
  bigTime: { color: colors.primary, fontSize: 40, fontWeight: '800', marginBottom: spacing.md },
  timeButton: {
    backgroundColor: colors.surfaceAlt, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.pill,
    paddingVertical: spacing.sm, paddingHorizontal: spacing.lg, alignItems: 'center',
  },
  timeButtonText: { color: colors.textPrimary, fontSize: 15, fontWeight: '700' },
  primaryButtonSpacing: { marginTop: spacing.lg, marginBottom: spacing.xs },
});
