import React, { useEffect, useRef, useState } from 'react';
import {
  Modal,
  NativeScrollEvent,
  NativeSyntheticEvent,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radii, shadow, spacing, type } from '../theme';
import { PrimaryButton, GhostButton } from './Button';

const ITEM_HEIGHT = 46;
const VISIBLE_ROWS = 5;
const PADDING_ROWS = Math.floor(VISIBLE_ROWS / 2);
const WHEEL_HEIGHT = ITEM_HEIGHT * VISIBLE_ROWS;

const HOURS = Array.from({ length: 12 }, (_, i) => i + 1); // 1..12
const MINUTES = Array.from({ length: 60 }, (_, i) => i); // 0..59

function clamp(n: number, min: number, max: number) {
  return Math.max(min, Math.min(max, n));
}

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

interface WheelColumnProps {
  values: number[];
  index: number;
  onChangeIndex: (index: number) => void;
  /** Bumped by the parent whenever the wheel must jump to a new index
   * programmatically (modal just opened) rather than from user scrolling. */
  resetToken: number;
}

function WheelColumn({ values, index, onChangeIndex, resetToken }: WheelColumnProps) {
  const scrollRef = useRef<ScrollView>(null);
  const isUserDriven = useRef(false);

  useEffect(() => {
    isUserDriven.current = false;
    scrollRef.current?.scrollTo({ y: index * ITEM_HEIGHT, animated: false });
    // Only re-sync on an explicit reset (modal opened with a new time), or
    // on mount — NOT on every `index` change, since most of those changes
    // originate from the user's own scroll and re-snapping mid-gesture
    // would fight the gesture.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetToken]);

  function commitFromOffset(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const y = e.nativeEvent.contentOffset.y;
    const next = clamp(Math.round(y / ITEM_HEIGHT), 0, values.length - 1);
    isUserDriven.current = true;
    onChangeIndex(next);
    scrollRef.current?.scrollTo({ y: next * ITEM_HEIGHT, animated: true });
  }

  function liveUpdateFromOffset(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const y = e.nativeEvent.contentOffset.y;
    const next = clamp(Math.round(y / ITEM_HEIGHT), 0, values.length - 1);
    if (next !== index) {
      isUserDriven.current = true;
      onChangeIndex(next);
    }
  }

  return (
    <View style={styles.wheelColumn}>
      <View pointerEvents="none" style={styles.wheelHighlight} />
      <ScrollView
        ref={scrollRef}
        showsVerticalScrollIndicator={false}
        snapToInterval={ITEM_HEIGHT}
        decelerationRate="fast"
        scrollEventThrottle={32}
        onScroll={liveUpdateFromOffset}
        onMomentumScrollEnd={commitFromOffset}
        onScrollEndDrag={commitFromOffset}
        contentContainerStyle={{ paddingVertical: ITEM_HEIGHT * PADDING_ROWS }}
      >
        {values.map((v, i) => (
          <View key={v} style={styles.wheelItem}>
            <Text style={[styles.wheelItemText, i === index && styles.wheelItemTextSelected]}>
              {String(v).padStart(2, '0')}
            </Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
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
 * existing habit — replaces the OS DateTimePicker dialog everywhere. UI is
 * always 12h ("6:00 PM"); onConfirm always reports 24h ('HH:mm'), matching
 * exactly what the backend's time_of_day column and the native alarm
 * scheduler already expect, so no other layer needed to change.
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
  const [hourIndex, setHourIndex] = useState(0);
  const [minuteIndex, setMinuteIndex] = useState(0);
  const [period, setPeriod] = useState<'AM' | 'PM'>('AM');
  const [resetToken, setResetToken] = useState(0);
  const insets = useSafeAreaInsets();

  useEffect(() => {
    if (!visible) return;
    const { hour12, minute, period: p } = from24h(initialTime);
    setHourIndex(hour12 - 1);
    setMinuteIndex(minute);
    setPeriod(p);
    setResetToken((t) => t + 1);
  }, [visible, initialTime]);

  const hour12 = HOURS[hourIndex] ?? 12;
  const minute = MINUTES[minuteIndex] ?? 0;

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
            {hour12}:{String(minute).padStart(2, '0')} {period}
          </Text>

          <View style={styles.pickerRow}>
            <WheelColumn values={HOURS} index={hourIndex} onChangeIndex={setHourIndex} resetToken={resetToken} />
            <Text style={styles.colon}>:</Text>
            <WheelColumn values={MINUTES} index={minuteIndex} onChangeIndex={setMinuteIndex} resetToken={resetToken} />

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
            onPress={() => onConfirm(to24h(hour12, minute, period))}
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
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    marginBottom: spacing.lg,
  },
  wheelColumn: {
    width: 64,
    height: WHEEL_HEIGHT,
  },
  wheelHighlight: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: ITEM_HEIGHT * PADDING_ROWS,
    height: ITEM_HEIGHT,
    borderRadius: radii.sm,
    backgroundColor: colors.primaryBg,
  },
  wheelItem: {
    height: ITEM_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
  wheelItemText: {
    fontSize: 19,
    fontWeight: '600',
    color: colors.textMuted,
  },
  wheelItemTextSelected: {
    color: colors.primary,
    fontSize: 23,
    fontWeight: '800',
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
