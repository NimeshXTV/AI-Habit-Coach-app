/**
 * Single shared visual language for the whole app — warm peach/cream,
 * coral-orange primary, golden accent, deep plum/brown text. Replaces the
 * earlier near-black "dashboard" theme entirely (see CLAUDE_CONTEXT.md).
 * Every screen must reference these tokens instead of inline hex values.
 *
 * Layout convention every screen follows (see components/ScreenSurface.tsx):
 * a peach `background` fills the device edge-to-edge, and a large rounded
 * cream `surface` "sheet" sits inset within it with a visible peach gutter
 * — that nested-card look is itself part of the design language, not just
 * a screenshot-mockup frame.
 */
export const colors = {
  // Outer app background (peach) vs. the cream "sheet" every screen's
  // content sits in, vs. individual cards on top of that sheet (brighter
  // white-cream, for contrast against the sheet).
  background: '#F0BC9C',
  backgroundDark: '#E4A57D',
  surface: '#FBF1E3',
  surfaceAlt: '#F5E6D2',
  surfaceCard: '#FFFCF7',

  border: '#EEDCC4',

  textHeading: '#1E1412',
  textPrimary: '#3B2A2E',
  textSecondary: '#8B6F63',
  textMuted: '#BBA28F',

  primary: '#EA6C42',
  primaryDark: '#C1502E',
  primaryBg: 'rgba(234,108,66,0.10)',
  onPrimary: '#FFFFFF',

  accentGold: '#F7C548',
  accentGoldDark: '#D9A428',
  onAccentGold: '#3B2A1A',

  deepPlum: '#3B2740',

  positive: '#6FA85C',
  positiveBg: 'rgba(111,168,92,0.14)',
  onPositive: '#FFFFFF',
  negative: '#C94F4F',
  negativeBg: 'rgba(201,79,79,0.14)',
  onNegative: '#FFFFFF',

  shadow: '#5C3A26',

  /** Habit Detail / Coach screen only (see CLAUDE_CONTEXT.md's UI redesign
   * notes) — that screen's own reference is a warm gold/amber dashboard of
   * individually-floating cards, not the single peach+cream-sheet
   * composition every other screen uses. Kept as its own token rather than
   * overriding `background` globally, since the rest of the app stays on
   * the peach/cream language. */
  habitPageBg: '#F8D999',
} as const;

export const radii = {
  sm: 10,
  md: 16,
  lg: 24,
  xl: 32,
  pill: 999,
  round: 999,
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 44,
} as const;

/**
 * No custom font is bundled — adding one (e.g. via @expo-google-fonts) would
 * touch native autolinking in this bare/prebuilt Android project for a
 * cosmetic gain, so this leans on bold system-font weights + generous sizes
 * to read as playful/rounded instead (see CLAUDE_CONTEXT.md's UI redesign
 * notes for the reasoning).
 */
export const type = {
  display: { fontSize: 34, fontWeight: '800' as const, color: colors.textHeading },
  h1: { fontSize: 24, fontWeight: '800' as const, color: colors.textHeading },
  h2: { fontSize: 18, fontWeight: '700' as const, color: colors.textHeading },
  body: { fontSize: 15, fontWeight: '500' as const, color: colors.textPrimary },
  bodyBold: { fontSize: 15, fontWeight: '700' as const, color: colors.textPrimary },
  small: { fontSize: 13, fontWeight: '500' as const, color: colors.textSecondary },
  tiny: { fontSize: 11.5, fontWeight: '600' as const, color: colors.textSecondary },
  button: { fontSize: 16, fontWeight: '800' as const, letterSpacing: 0.2 },
};

export const shadow = {
  card: {
    shadowColor: colors.shadow,
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.1,
    shadowRadius: 14,
    elevation: 3,
  },
  button: {
    shadowColor: colors.primaryDark,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.22,
    shadowRadius: 8,
    elevation: 4,
  },
  soft: {
    shadowColor: colors.shadow,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 6,
    elevation: 1,
  },
} as const;
