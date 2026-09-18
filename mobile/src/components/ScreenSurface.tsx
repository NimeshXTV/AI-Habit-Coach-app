import React from 'react';
import { ScrollView, StyleSheet, View, ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radii, spacing } from '../theme';

interface Props {
  children: React.ReactNode;
  /** Extra decorative elements (Blob/ContourLines/...) rendered inside the
   * cream sheet, behind `children` — pass them positioned absolutely. */
  decorations?: React.ReactNode;
  scroll?: boolean;
  contentStyle?: ViewStyle;
}

/**
 * The nested "peach background + inset rounded cream sheet" composition
 * every screen in the app uses (see theme.ts's layout-convention note) —
 * centralized here so it's defined once instead of duplicated per screen.
 */
export default function ScreenSurface({ children, decorations, scroll = false, contentStyle }: Props) {
  const insets = useSafeAreaInsets();
  const Content = scroll ? ScrollView : View;
  const contentProps = scroll
    ? { contentContainerStyle: [styles.content, contentStyle], showsVerticalScrollIndicator: false }
    : { style: [styles.content, contentStyle] };

  return (
    <View
      style={[
        styles.outer,
        { paddingTop: insets.top + 14, paddingBottom: insets.bottom + 14 },
      ]}
    >
      <View style={styles.sheet}>
        {decorations}
        <Content {...(contentProps as object)}>{children}</Content>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  outer: { flex: 1, backgroundColor: colors.background, paddingHorizontal: 14 },
  sheet: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: radii.xl,
    overflow: 'hidden',
  },
  content: { flexGrow: 1, padding: spacing.lg },
});
