import React from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, ViewStyle } from 'react-native';
import { colors, radii, shadow, type } from '../theme';

interface ButtonProps {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  loading?: boolean;
  style?: ViewStyle;
  /** Smaller inline variant (e.g. "Save"/"Confirm time" next to a Cancel) instead of the full-width pill. */
  compact?: boolean;
}

/** The coral pill button used for every primary action across the app
 * (Let's get started / Continue / Done / Add new / ...). */
export function PrimaryButton({ label, onPress, disabled, loading, style, compact }: ButtonProps) {
  return (
    <TouchableOpacity
      style={[styles.primary, compact && styles.compact, (disabled || loading) && styles.disabled, style]}
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.85}
    >
      {loading ? <ActivityIndicator color={colors.onPrimary} /> : <Text style={styles.primaryText}>{label}</Text>}
    </TouchableOpacity>
  );
}

/** Plain-text secondary action (Cancel / Back / Stop here). */
export function GhostButton({ label, onPress, disabled, style }: Omit<ButtonProps, 'loading' | 'compact'>) {
  return (
    <TouchableOpacity style={[styles.ghost, style]} onPress={onPress} disabled={disabled} activeOpacity={0.6}>
      <Text style={styles.ghostText}>{label}</Text>
    </TouchableOpacity>
  );
}

/** A rounded, bordered "chip" style button — used for option lists (gender
 * picker, snooze duration, feedback reasons, reschedule choices). */
export function ChipButton({
  label, onPress, disabled, selected, tone = 'default',
}: { label: string; onPress: () => void; disabled?: boolean; selected?: boolean; tone?: 'default' | 'primary' }) {
  return (
    <TouchableOpacity
      style={[
        styles.chip,
        selected && styles.chipSelected,
        tone === 'primary' && styles.chipPrimary,
      ]}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.75}
    >
      <Text style={[styles.chipText, tone === 'primary' && styles.chipPrimaryText]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  primary: {
    backgroundColor: colors.primary,
    borderRadius: radii.pill,
    paddingVertical: 17,
    alignItems: 'center',
    justifyContent: 'center',
    ...shadow.button,
  },
  compact: { flex: 1, paddingVertical: 13 },
  disabled: { opacity: 0.55 },
  primaryText: { color: colors.onPrimary, ...type.button },
  ghost: { paddingVertical: 14, alignItems: 'center' },
  ghostText: { color: colors.textSecondary, fontSize: 14.5, fontWeight: '700' },
  chip: {
    backgroundColor: colors.surfaceCard,
    borderWidth: 1.5,
    borderColor: colors.border,
    borderRadius: radii.pill,
    paddingVertical: 10,
    paddingHorizontal: 16,
  },
  chipSelected: { borderColor: colors.primary, backgroundColor: colors.primaryBg },
  chipPrimary: { backgroundColor: colors.positive, borderColor: colors.positive },
  chipText: { color: colors.textPrimary, fontSize: 13.5, fontWeight: '700' },
  chipPrimaryText: { color: colors.onPositive },
});
