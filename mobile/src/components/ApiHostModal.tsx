import React, { useEffect, useState } from 'react';
import { Modal, StyleSheet, Text, TextInput, TouchableWithoutFeedback, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radii, shadow, spacing, type } from '../theme';
import { PrimaryButton, GhostButton } from './Button';
import { EMULATOR_DEFAULT_HOST, getApiHost, runBackendDiscovery, setApiHost } from '../apiConfig';

interface Props {
  visible: boolean;
  onClose: () => void;
}

/**
 * Lets the backend host be changed at runtime instead of being a hardcoded
 * LAN IP baked into a build (see CLAUDE_CONTEXT.md's networking fix). This
 * is now the FALLBACK path, not the primary one — the app auto-discovers
 * the backend over the LAN on its own (apiConfig.ts/discovery.ts) whenever
 * no host is pinned here, so this field only needs to be touched on a
 * network where broadcast discovery doesn't reach (e.g. an isolated guest
 * Wi-Fi), or to force a specific host.
 */
export default function ApiHostModal({ visible, onClose }: Props) {
  const insets = useSafeAreaInsets();
  const [value, setValue] = useState('');
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    if (visible) {
      setScanning(false);
      getApiHost().then(setValue);
    }
  }, [visible]);

  async function save() {
    await setApiHost(value);
    onClose();
  }

  /** Clears any manual pin so getApiHost() falls back to whatever LAN
   * discovery finds (or the emulator default if nothing is found), then
   * immediately triggers a fresh scan so the field reflects the result
   * without the user needing to close/reopen the modal. */
  async function useAutoDetect() {
    await setApiHost('');
    setScanning(true);
    await runBackendDiscovery();
    setValue(await getApiHost());
    setScanning(false);
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose} statusBarTranslucent>
      <TouchableWithoutFeedback onPress={onClose}>
        <View style={styles.backdrop} />
      </TouchableWithoutFeedback>
      <View style={styles.sheetWrap} pointerEvents="box-none">
        <View style={[styles.sheet, { paddingBottom: spacing.xl + insets.bottom }]}>
          <Text style={styles.title}>Backend server</Text>
          <Text style={styles.hint}>
            The app auto-detects the backend over Wi-Fi/hotspot on its own — you only need to set this manually if
            that doesn't work on your current network. IP address or hostname, e.g. 192.168.1.42. The app works fine
            offline either way.
          </Text>
          <TextInput
            style={styles.input}
            placeholder={EMULATOR_DEFAULT_HOST}
            placeholderTextColor={colors.textMuted}
            value={value}
            onChangeText={setValue}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
          />
          <PrimaryButton label="Save" onPress={save} style={styles.saveButton} />
          <GhostButton
            label={scanning ? 'Scanning…' : 'Use auto-detect'}
            onPress={useAutoDetect}
            disabled={scanning}
          />
          <GhostButton label="Cancel" onPress={onClose} />
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
  title: { ...type.h1, fontSize: 20 },
  hint: { ...type.small, marginTop: spacing.xs, marginBottom: spacing.md, lineHeight: 18 },
  input: {
    backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.md,
    color: colors.textPrimary, padding: 14, fontSize: 15.5, fontWeight: '500', marginBottom: spacing.md,
  },
  saveButton: { marginBottom: spacing.xs },
});
