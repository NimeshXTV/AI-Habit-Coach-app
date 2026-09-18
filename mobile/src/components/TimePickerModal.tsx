import React, { useEffect, useRef, useState } from 'react';
import {
  Modal,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radii, shadow, spacing, type } from '../theme';
import { PrimaryButton, GhostButton } from './Button';

/** Parses a backend 'HH:mm' 24h string into { hour12, minute, period}. */
function from24h(hhmm: string): { hour12: number; minute: number; period: 'AM' | 'PM' } {
  const [h, m] = hhmm.split(':').map(Number);
  const period: 'AM' | 'PM' = h >= 12 ? 'PM' : 'AM';
  const hour12 = h % 12 || 12;
  return { hour12, minute: m, period };
}

/** Inverse of from24h — this is the ONLY place a picker selection is turned
 * back into the 'HH:mm' 24h string the backend/alarm scheduling expects. */
function to24h(hour12: number, minute: number, period: 'AM' | 'PM'): string {
  const h = period === 'AM' ? hour12 % 12 : (hour12 % 12) + 12;
  return `${String(h).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function clamp(n: number, min: number, max: number) {
  return Math.max(min, Math.min(max, n));
}

/** Keeps only digits and caps length — used while the user is still
 * typing, before any clamping/defaulting happens (that only happens on
 * blur/confirm, so partial input like "" or "1" isn't fought mid-type). */
function digitsOnly(text: string, maxLength: number): string {
  return text.replace(/[^0-9]/g, '').slice(0, maxLength);
}

interface TimePickerModalProps {
  visible: boolean;
  /** Current/starting time as backend 'HH:mm' 24h — the picker always opens
   * showing this value. */
  initialTime: string;
  title?: string;
  subtitle?: string;
  hint?: string;
  confirmLabel?: string;
  busy?: boolean;
  onCancel: () => void;
  /** Receives the picked time as 'HH:mm' 24h — the same representation the
   * backend/alarm scheduling already expects, never the 12h display string. */
  onConfirm: (timeOfDay24h: string) => void;
}

/**
 * The single reusable in-app time picker used by both Create Challenge
 * (when the AI couldn't find a time in the goal text) and Change Time on an
 * existing habit — replaces the OS DateTimePicker dialog everywhere. Entry
 * is by TYPING hour/minute directly (two number-pad text fields) rather
 * than a scroll wheel — a deliberate UX preference change from the
 * original wheel-picker design. UI is always 12h ("6:00 PM"); onConfirm
 * always reports 24h ('HH:mm'), matching exactly what the backend's
 * time_of_day column and the native alarm scheduler already expect, so no
 * other layer needed to change.
 */
export default function TimePickerModal({
  visible,
  initialTime,
  title = "When should we\nremind you?",
  subtitle,
  hint,
  confirmLabel = 'Confirm time',
  busy,
  onCancel,
  onConfirm,
}: TimePickerModalProps) {
  const [hourText, setHourText] = useState('12');
  const [minuteText, setMinuteText] = useState('00');
  const [period, setPeriod] = useState<'AM' | 'PM'>('AM');
  const insets = useSafeAreaInsets();
  const minuteInputRef = useRef<TextInput>(null);

  useEffect(() => {
    if (!visible) return;
    const { hour12, minute, period: p } = from24h(initialTime);
    setHourText(String(hour12));
    setMinuteText(String(minute).padStart(2, '0'));
    setPeriod(p);
  }, [visible, initialTime]);

  // Live preview reflects whatever is currently typed, clamped only for
  // DISPLAY — the text fields themselves are left exactly as typed so
  // backspacing/retyping isn't fought (clamping/defaulting for the actual
  // submitted value happens in confirm() below).
  const previewHour = clamp(parseInt(hourText, 10) || 12, 1, 12);
  const previewMinute = clamp(parseInt(minuteText, 10) || 0, 0, 59);

  function handleHourChange(text: string) {
    const digits = digitsOnly(text, 2);
    setHourText(digits);
    // Auto-advance to the minute field once a 2-digit hour (or any value
    // >= 2, which can't take a second digit and still be <= 12) is typed —
    // saves a manual tap between the two fields.
    if (digits.length === 2 || parseInt(digits, 10) > 1) {
      minuteInputRef.current?.focus();
    }
  }

  function handleHourBlur() {
    const clamped = clamp(parseInt(hourText, 10) || 12, 1, 12);
    setHourText(String(clamped));
  }

  function handleMinuteBlur() {
    const clamped = clamp(parseInt(minuteText, 10) || 0, 0, 59);
    setMinuteText(String(clamped).padStart(2, '0'));
  }

  function confirm() {
    const hour12 = clamp(parseInt(hourText, 10) || 12, 1, 12);
    const minute = clamp(parseInt(minuteText, 10) || 0, 0, 59);
    onConfirm(to24h(hour12, minute, period));
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onCancel} statusBarTranslucent>
      <TouchableWithoutFeedback onPress={onCancel}>
        <View style={styles.backdrop} />
      </TouchableWithoutFeedback>
      <View style={styles.sheetWrap} pointerEvents="box-none">
        <View style={[styles.sheet, { paddingBottom: spacing.xl + insets.bottom }]}>
          <Text style={styles.title}>{title}</Text>
          {subtitle && <Text style={styles.subtitle}>{subtitle}</Text>}
          {hint && <Text style={styles.hint}>{hint}</Text>}

          <Text style={styles.bigTime}>
            {previewHour}:{String(previewMinute).padStart(2, '0')} {period}
          </Text>

          <View style={styles.pickerRow}>
            <TextInput
              style={styles.timeInput}
              value={hourText}
              onChangeText={handleHourChange}
              onBlur={handleHourBlur}
              keyboardType="number-pad"
              maxLength={2}
              selectTextOnFocus
              returnKeyType="next"
              onSubmitEditing={() => minuteInputRef.current?.focus()}
              accessibilityLabel="Hour"
            />
            <Text style={styles.colon}>:</Text>
            <TextInput
              ref={minuteInputRef}
              style={styles.timeInput}
              value={minuteText}
              onChangeText={(text) => setMinuteText(digitsOnly(text, 2))}
              onBlur={handleMinuteBlur}
              keyboardType="number-pad"
              maxLength={2}
              selectTextOnFocus
              returnKeyType="done"
              onSubmitEditing={handleMinuteBlur}
              accessibilityLabel="Minute"
            />

            <View style={styles.periodColumn}>
              <TouchableOpacity
                style={[styles.periodButton, period === 'AM' && styles.periodButtonSelected]}
                onPress={() => setPeriod('AM')}
                activeOpacity={0.75}
              >
                <Text style={[styles.periodButtonText, period === 'AM' && styles.periodButtonTextSelected]}>AM</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.periodButton, period === 'PM' && styles.periodButtonSelected]}
                onPress={() => setPeriod('PM')}
                activeOpacity={0.75}
              >
                <Text style={[styles.periodButtonText, period === 'PM' && styles.periodButtonTextSelected]}>PM</Text>
              </TouchableOpacity>
            </View>
          </View>

          <PrimaryButton
            label={confirmLabel}
            onPress={confirm}
            loading={busy}
            style={styles.confirmButton}
          />
          <GhostButton label="Cancel" onPress={onCancel} />
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFill,
    backgroundColor: 'rgba(30,20,18,0.45)',
  },
  sheetWrap: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radii.xl,
    borderTopRightRadius: radii.xl,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    // paddingBottom is applied inline (see the sheet's style prop) — it
    // must include useSafeAreaInsets().bottom for gesture-nav-bar clearance.
    ...shadow.card,
  },
  title: { ...type.h1, fontSize: 22, textAlign: 'center' },
  subtitle: { ...type.small, textAlign: 'center', marginTop: spacing.xs },
  hint: { ...type.small, fontSize: 12, textAlign: 'center', marginTop: spacing.xs, paddingHorizontal: spacing.sm },
  bigTime: {
    color: colors.primary,
    fontSize: 40,
    fontWeight: '800',
    textAlign: 'center',
    marginTop: spacing.md,
    marginBottom: spacing.sm,
  },
  pickerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
    borderRadius: radii.lg,
    borderWidth: 1.5,
    borderColor: colors.border,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.md,
    marginBottom: spacing.lg,
  },
  timeInput: {
    width: 64,
    height: 56,
    borderRadius: radii.sm,
    borderWidth: 1.5,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    textAlign: 'center',
    fontSize: 23,
    fontWeight: '800',
    color: colors.primary,
  },
  colon: {
    fontSize: 24,
    fontWeight: '800',
    color: colors.textPrimary,
    marginHorizontal: spacing.xs,
  },
  periodColumn: {
    marginLeft: spacing.md,
    gap: spacing.xs,
  },
  periodButton: {
    borderWidth: 1.5,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    borderRadius: radii.sm,
    paddingVertical: 10,
    paddingHorizontal: 14,
    alignItems: 'center',
  },
  periodButtonSelected: {
    borderColor: colors.primary,
    backgroundColor: colors.primary,
  },
  periodButtonText: { fontSize: 14, fontWeight: '800', color: colors.textSecondary },
  periodButtonTextSelected: { color: colors.onPrimary },
  confirmButton: { marginTop: spacing.xs, marginBottom: spacing.xs },
});
