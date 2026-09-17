import React from 'react';
import { StyleSheet, View, ViewStyle } from 'react-native';
import { colors } from '../theme';

/**
 * Reusable decorative primitives for the "warm/playful/hand-drawn" visual
 * language (see the Welcome/My Challenges reference screenshots). No SVG
 * library is used deliberately — react-native-svg would add a native
 * module to a bare/prebuilt Android project (committed android/ folder)
 * for a purely cosmetic gain, so every shape here is a plain styled View:
 * asymmetric corner radii + rotation for organic "blob" shapes, and thin
 * partial-border arcs for the faint contour-line texture. All of these are
 * `pointerEvents="none"` and meant to sit BEHIND real content (per the
 * accessibility/readability requirement) — never place one on top of
 * interactive elements without also lowering opacity and z-index below 0.
 */

interface BlobProps {
  size: number;
  color?: string;
  style?: ViewStyle;
  rotation?: number;
}

/** An irregular, petal-like rounded shape — the "organic blob" accent used
 * throughout the reference screenshots (top-right of Welcome, the card
 * accents on My Challenges, ...). */
export function Blob({ size, color = colors.accentGold, style, rotation = 0 }: BlobProps) {
  return (
    <View
      pointerEvents="none"
      style={[
        {
          width: size,
          height: size,
          backgroundColor: color,
          borderTopLeftRadius: size * 0.55,
          borderTopRightRadius: size * 0.4,
          borderBottomRightRadius: size * 0.5,
          borderBottomLeftRadius: size * 0.42,
          transform: [{ rotate: `${rotation}deg` }],
        },
        style,
      ]}
    />
  );
}

/** A small filled circle — used for the tiny scattered accent dots. */
export function Dot({ size = 10, color = colors.primary, style }: { size?: number; color?: string; style?: ViewStyle }) {
  return (
    <View
      pointerEvents="none"
      style={[{ width: size, height: size, borderRadius: size / 2, backgroundColor: color }, style]}
    />
  );
}

/** A single faint circular arc, built from a mostly-transparent circle with
 * only part of its border visible — combined at different sizes/rotations
 * (see ContourLines) this reads as the hand-drawn topographic squiggles in
 * the reference screenshots, without needing an SVG path. */
function Arc({ size, style, rotation = 0 }: { size: number; style?: ViewStyle; rotation?: number }) {
  return (
    <View
      pointerEvents="none"
      style={[
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          borderWidth: 1,
          borderColor: colors.textMuted,
          opacity: 0.22,
          borderRightColor: 'transparent',
          borderBottomColor: 'transparent',
          transform: [{ rotate: `${rotation}deg` }],
        },
        style,
      ]}
    />
  );
}

/** A loose cluster of faint arcs standing in for the reference screenshots'
 * hand-drawn contour-line background texture. Purely decorative, absolutely
 * positioned by the caller's `style`, always behind content. */
export function ContourLines({ style }: { style?: ViewStyle }) {
  return (
    <View pointerEvents="none" style={[styles.contourWrap, style]}>
      <Arc size={260} rotation={20} style={{ position: 'absolute', top: -40, left: -60 }} />
      <Arc size={200} rotation={65} style={{ position: 'absolute', top: 30, left: 10 }} />
      <Arc size={320} rotation={-15} style={{ position: 'absolute', top: 120, left: -100 }} />
    </View>
  );
}

const styles = StyleSheet.create({
  contourWrap: { position: 'absolute', overflow: 'hidden' },
});
