import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '../api';
import { cancelDailyAlarm, cancelSnoozeAlarm } from '../notifications';
import { Blob, Dot } from '../components/decor';
import ScreenSurface from '../components/ScreenSurface';
import { PrimaryButton } from '../components/Button';
import { colors, radii, shadow, spacing, treeStageColors, treeStageLabels, type } from '../theme';
import type { Habit, HabitDay } from '../types';

/** Rotates through the app's own accent triad for each card's decorative
 * blob, same as the reference screenshot's 3 cards (yellow / coral / plum) —
 * independent of tree_stage, which drives the blob's actual color below;
 * these are only the small companion dots. */
const DOT_VARIANTS = [colors.accentGold, colors.primary, colors.deepPlum];

interface Props {
  activeHabitId: number | null;
  onOpen: (id: number) => void;
  onCreateNew: () => void;
  /** Fired when the currently-active challenge got deleted, so the parent
   * can update which habit is "active" WITHOUT navigating away from this
   * list — the user is actively managing challenges here. */
  onActiveHabitIdChange: (id: number | null) => void;
}

function dayNumberOf(days: HabitDay[], totalDays: number): number {
  const pending = days.find((d) => d.status === 'pending');
  return pending ? pending.day_number : totalDays;
}

export default function ChallengesScreen({ activeHabitId, onOpen, onCreateNew, onActiveHabitIdChange }: Props) {
  const [habits, setHabits] = useState<Habit[] | null>(null);
  const [progress, setProgress] = useState<Record<number, number>>({});
  const [busyId, setBusyId] = useState<number | null>(null);
  const [name, setName] = useState<string | null>(null);

  const load = useCallback(async () => {
    const list = await api.listHabits();
    setHabits(list);
    const entries = await Promise.all(
      list.map(async (h) => {
        const detail = await api.getHabitDetail(h.id);
        return [h.id, dayNumberOf(detail.days, h.total_days)] as const;
      })
    );
    setProgress(Object.fromEntries(entries));
  }, []);

  useEffect(() => {
    load();
    api.getProfile().then((p) => setName(p?.name ?? null)).catch(() => setName(null));
  }, [load]);

  function confirmDelete(habit: Habit) {
    Alert.alert('Delete this challenge?', `"${habit.name}"`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => doDelete(habit) },
    ]);
  }

  async function doDelete(habit: Habit) {
    setBusyId(habit.id);
    try {
      // Cancel this challenge's Android alarms BEFORE removing the data —
      // both are scoped by habit id, so this never touches other challenges.
      await cancelDailyAlarm(habit.id);
      await cancelSnoozeAlarm(habit.id);
      await api.deleteHabit(habit.id);

      const remaining = (habits ?? []).filter((h) => h.id !== habit.id);
      if (activeHabitId === habit.id) {
        onActiveHabitIdChange(remaining.length ? remaining[0].id : null);
      }
      await load();
    } finally {
      setBusyId(null);
    }
  }

  if (!habits) {
    return (
      <ScreenSurface>
        <View style={styles.center}>
          <ActivityIndicator color={colors.primary} />
        </View>
      </ScreenSurface>
    );
  }

  return (
    <ScreenSurface
      decorations={<Blob size={140} color={colors.accentGold} rotation={-6} style={styles.headerBlob} />}
    >
      <View style={styles.brandRow}>
        <View style={styles.brandDot} />
        <Text style={styles.brandText}>Habit Coach</Text>
      </View>
      <Text style={styles.habitHeading}>Habit</Text>

      <View style={styles.greetingRow}>
        <Text style={styles.greeting} numberOfLines={1}>Hi {name ?? 'there'}!</Text>
        <View style={styles.avatarChip} />
      </View>

      <ScrollView style={styles.list} contentContainerStyle={styles.listContent} showsVerticalScrollIndicator={false}>
        {habits.length === 0 && (
          <Text style={styles.empty}>No challenges yet — tap "Add new" to start your first one.</Text>
        )}

        {habits.map((h, i) => {
          const blobColor = treeStageColors[h.tree_stage];
          const dotColor = DOT_VARIANTS[i % DOT_VARIANTS.length];
          return (
            <TouchableOpacity
              key={h.id}
              style={styles.card}
              onPress={() => onOpen(h.id)}
              onLongPress={() => confirmDelete(h)}
              activeOpacity={0.75}
            >
              {/* Large decorative blob bleeding off the card's top-right
                  corner, per the reference screenshot — purely decorative;
                  its color is the only thing tied to real data (tree_stage),
                  the actual health/stage/day info is the text below. */}
              <View style={styles.cardBlobLayer} pointerEvents="none">
                <Blob size={132} color={blobColor} rotation={i % 2 === 0 ? -12 : 14} style={styles.cardBlobMain} />
                <Dot size={9} color={dotColor} style={styles.cardDotA} />
                {i % 3 !== 2 && <Dot size={7} color={colors.textMuted} style={styles.cardDotB} />}
              </View>

              {busyId === h.id ? (
                <ActivityIndicator color={colors.negative} size="small" style={styles.deleteButton} />
              ) : (
                <TouchableOpacity onPress={() => confirmDelete(h)} hitSlop={10} style={styles.deleteButton}>
                  <Text style={styles.deleteButtonText}>✕</Text>
                </TouchableOpacity>
              )}

              <View style={styles.cardTextBlock}>
                <Text style={styles.cardTitle} numberOfLines={2}>{h.emoji} {h.name}</Text>
                <View style={styles.cardMetaRow}>
                  <Text style={styles.cardMetaIcon}>📅</Text>
                  <Text style={styles.cardMeta}>{h.total_days} days · Day {progress[h.id] ?? '—'}</Text>
                </View>
                <Text style={styles.cardTreeCaption}>
                  {treeStageLabels[h.tree_stage]} · {h.tree_health}%
                </Text>
              </View>
            </TouchableOpacity>
          );
        })}
      </ScrollView>

      <PrimaryButton label="+ Add new" onPress={onCreateNew} style={styles.addButton} />
    </ScreenSurface>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  headerBlob: { position: 'absolute', top: -60, right: -50, opacity: 0.85 },
  brandRow: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.sm },
  brandDot: { width: 20, height: 20, borderRadius: 10, backgroundColor: colors.deepPlum, marginRight: spacing.xs },
  brandText: { color: colors.textSecondary, fontSize: 13, fontWeight: '600' },
  habitHeading: { ...type.display, fontSize: 30 },
  greetingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: spacing.sm, marginBottom: spacing.lg },
  greeting: { color: colors.accentGoldDark, fontSize: 21, fontWeight: '800', flex: 1 },
  avatarChip: { width: 36, height: 36, borderRadius: radii.sm, backgroundColor: colors.primary, ...shadow.soft },
  list: { flex: 1 },
  listContent: { paddingBottom: spacing.md },
  empty: { color: colors.textSecondary, fontSize: 14.5, marginTop: spacing.lg },
  card: {
    position: 'relative',
    justifyContent: 'flex-end',
    minHeight: 132,
    backgroundColor: colors.surfaceCard,
    borderRadius: radii.lg,
    padding: spacing.md,
    marginBottom: spacing.md,
    overflow: 'hidden',
    ...shadow.soft,
  },
  cardBlobLayer: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  cardBlobMain: { position: 'absolute', top: -28, right: -34, opacity: 0.92 },
  cardDotA: { position: 'absolute', top: 14, right: 74 },
  cardDotB: { position: 'absolute', top: 60, right: 20 },
  cardTextBlock: { maxWidth: '68%' },
  cardTitle: { ...type.bodyBold, fontSize: 16.5 },
  cardMetaRow: { flexDirection: 'row', alignItems: 'center', marginTop: spacing.xs },
  cardMetaIcon: { fontSize: 12, marginRight: 4 },
  cardMeta: { ...type.tiny },
  cardTreeCaption: { ...type.tiny, color: colors.textMuted, marginTop: 2, textTransform: 'capitalize' },
  deleteButton: {
    position: 'absolute', top: spacing.sm, right: spacing.sm, width: 24, height: 24, borderRadius: 12,
    backgroundColor: 'rgba(255,252,247,0.75)', alignItems: 'center', justifyContent: 'center', zIndex: 2,
  },
  deleteButtonText: { color: colors.textSecondary, fontSize: 12, fontWeight: '700' },
  addButton: { marginTop: spacing.sm },
});
