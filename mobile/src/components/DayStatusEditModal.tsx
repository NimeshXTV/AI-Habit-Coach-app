import React from 'react';
import { Modal, StyleSheet, Text, TouchableOpacity, TouchableWithoutFeedback, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radii, shadow, spacing, type } from '../theme';
import { GhostButton } from './Button';

export type EditableDayStatus = 'pending' | 'done' | 'missed';

interface Props {
  visible: boolean;
  dayNumber: number | null;
  /** Real calendar date this cell corresponds to (see HabitScreen.tsx's
   * dayNumbersByDate) — shown so the user can confirm exactly which day
   * they're about to change, which is the whole point of this feature
   * (see CLAUDE_CONTEXT.md's "wrong day got marked" concern). */
  date: Date | null;
  currentStatus: EditableDayStatus | 'snoozed' | null;
  busy?: boolean;
  onCancel: () => void;
  onChoose: (status: EditableDayStatus) => void;
}

const STATUS_LABEL: Record<string, string> = {
  pending: 'Pending',
  done: 'Done',
  missed: 'Missed',
  snoozed: 'Snoozed',
};

/**
 * Manual calendar-tap correction sheet — lets the user set a SPECIFIC
 * day's status directly (mirrors TimePickerModal.tsx's bottom-sheet
 * pattern: transparent slide-up Modal, backdrop-tap-to-cancel). This is an
 * additive correction mechanism alongside the existing Done/Snooze/Missed
 * buttons on the ringing card, not a replacement — see HabitScreen.tsx for
 * which cells are eligible to open this at all (already-reached days on
 * an active habit only).
 */
export default function DayStatusEditModal({ visible, dayNumber, date, currentStatus, busy, onCancel, onChoose }: Props) {
  const insets = useSafeAreaInsets();
  const dateLabel = date ? date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }) : '';

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onCancel} statusBarTranslucent>
      <TouchableWithoutFeedback onPress={onCancel}>
        <View style={styles.backdrop} />
      </TouchableWithoutFeedback>
      <View style={styles.sheetWrap} pointerEvents="box-none">
        <View style={[styles.sheet, { paddingBottom: spacing.xl + insets.bottom }]}>
          <Text style={styles.title}>Day {dayNumber}</Text>
          {!!dateLabel && <Text style={styles.subtitle}>{dateLabel}</Text>}
          {!!currentStatus && (
            <Text style={styles.currentStatus}>Currently: {STATUS_LABEL[currentStatus] ?? currentStatus}</Text>
          )}

          <TouchableOpacity
            style={[styles.choiceRow, styles.choiceDone]}
            onPress={() => onChoose('done')}
            disabled={busy}
            activeOpacity={0.8}
          >
            <Text style={styles.choiceTextDone}>✓ Mark Done</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.choiceRow, styles.choiceMissed]}
            onPress={() => onChoose('missed')}
            disabled={busy}
            activeOpacity={0.8}
          >
            <Text style={styles.choiceTextMissed}>✕ Mark Missed</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.choiceRow, styles.choicePending]}
            onPress={() => onChoose('pending')}
            disabled={busy}
            activeOpacity={0.8}
          >
            <Text style={styles.choiceTextPending}>↺ Reset to Pending</Text>
          </TouchableOpacity>

          <GhostButton label="Cancel" onPress={onCancel} disabled={busy} />
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(30,20,18,0.45)' },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radii.xl,
    borderTopRightRadius: radii.xl,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    ...shadow.card,
  },
  title: { ...type.h1, fontSize: 22, textAlign: 'center' },
  subtitle: { ...type.small, textAlign: 'center', marginTop: spacing.xs },
  currentStatus: { ...type.tiny, textAlign: 'center', marginTop: spacing.xs, marginBottom: spacing.md },

  choiceRow: {
    borderRadius: radii.md, borderWidth: 1.5, paddingVertical: 14, alignItems: 'center', marginBottom: spacing.sm,
  },
  choiceDone: { backgroundColor: colors.positiveBg, borderColor: colors.positive },
  choiceTextDone: { color: colors.positive, fontWeight: '800', fontSize: 15 },
  choiceMissed: { backgroundColor: colors.negativeBg, borderColor: colors.negative },
  choiceTextMissed: { color: colors.negative, fontWeight: '800', fontSize: 15 },
  choicePending: { backgroundColor: colors.surfaceCard, borderColor: colors.border },
  choiceTextPending: { color: colors.textPrimary, fontWeight: '800', fontSize: 15 },
});
