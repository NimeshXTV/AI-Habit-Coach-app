import React, { useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, TextInput, View } from 'react-native';
import { api } from '../api';
import { Blob, ContourLines } from '../components/decor';
import ScreenSurface from '../components/ScreenSurface';
import { ChipButton, GhostButton, PrimaryButton } from '../components/Button';
import { colors, radii, spacing, type } from '../theme';
import type { Gender } from '../types';

/**
 * First-launch-only flow (see App.tsx: shown iff GET /api/profile 404s).
 * Welcome -> Name -> Age -> Gender -> save -> My Challenges. Visual
 * language matches the Welcome/My Challenges reference screenshots; kept
 * functionally identical to the previous plain version (same api.saveProfile
 * call, same validation), only the presentation changed.
 */
type Step = 'welcome' | 'name' | 'age' | 'gender';

interface Props {
  onDone: () => void;
}

const GENDERS: { value: Gender; label: string }[] = [
  { value: 'male', label: 'Male' },
  { value: 'female', label: 'Female' },
  { value: 'other', label: 'Others' },
];

export default function OnboardingScreen({ onDone }: Props) {
  const [step, setStep] = useState<Step>('welcome');
  const [name, setName] = useState('');
  const [age, setAge] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function finish() {
    if (!gender) return;
    const parsedAge = parseInt(age, 10);
    setBusy(true);
    setError(null);
    try {
      await api.saveProfile(name.trim(), parsedAge, gender);
      onDone();
    } catch (e) {
      setError('Could not save your profile — check your connection and try again.');
    } finally {
      setBusy(false);
    }
  }

  if (step === 'welcome') {
    return (
      <ScreenSurface
        decorations={
          <>
            <Blob size={220} color={colors.accentGold} rotation={-10} style={styles.welcomeTopBlob} />
            <ContourLines style={styles.welcomeContours} />
            <Blob size={110} color={colors.primary} rotation={6} style={styles.welcomeLeftBlob} />
            <Blob size={260} color={colors.deepPlum} rotation={0} style={styles.welcomeBottomBlob} />
          </>
        }
      >
        <View style={styles.welcomeSpacerTop} />
        <View style={styles.welcomeTextBlock}>
          <Text style={type.display}>Welcome</Text>
          <Text style={styles.welcomeSubtitle}>Let's build a habit{'\n'}together</Text>
        </View>
        <PrimaryButton label="Let's get started" onPress={() => setStep('name')} />
        <View style={styles.welcomeSpacerBottom} />
      </ScreenSurface>
    );
  }

  return (
    <ScreenSurface
      decorations={
        <>
          <Blob size={130} color={colors.accentGold} rotation={-8} style={styles.stepBlob} />
          <ContourLines style={styles.stepContours} />
        </>
      }
    >
      <View style={styles.stepSpacerTop} />

      {step === 'name' && (
        <>
          <Text style={type.h1}>What should we call you?</Text>
          <TextInput
            style={styles.input}
            placeholder="Your name"
            placeholderTextColor={colors.textMuted}
            value={name}
            onChangeText={setName}
            autoFocus
          />
          <PrimaryButton label="Continue" onPress={() => setStep('age')} disabled={!name.trim()} style={styles.continueButton} />
        </>
      )}

      {step === 'age' && (
        <>
          <Text style={type.h1}>How old are you?</Text>
          <TextInput
            style={styles.input}
            placeholder="Age"
            placeholderTextColor={colors.textMuted}
            value={age}
            onChangeText={(v) => setAge(v.replace(/[^0-9]/g, ''))}
            keyboardType="number-pad"
            maxLength={3}
            autoFocus
          />
          <PrimaryButton
            label="Continue"
            onPress={() => setStep('gender')}
            disabled={!age || Number(age) < 1 || Number(age) > 120}
            style={styles.continueButton}
          />
          <GhostButton label="Back" onPress={() => setStep('name')} />
        </>
      )}

      {step === 'gender' && (
        <>
          <Text style={type.h1}>How should we personalize your experience?</Text>
          <View style={styles.genderRow}>
            {GENDERS.map((g) => (
              <ChipButton
                key={g.value}
                label={g.label}
                selected={gender === g.value}
                onPress={() => setGender(g.value)}
                disabled={busy}
              />
            ))}
          </View>
          {error && <Text style={styles.error}>{error}</Text>}
          <PrimaryButton
            label="Continue"
            onPress={finish}
            disabled={!gender}
            loading={busy}
            style={styles.continueButton}
          />
          <GhostButton label="Back" onPress={() => setStep('age')} disabled={busy} />
        </>
      )}
    </ScreenSurface>
  );
}

const styles = StyleSheet.create({
  welcomeSpacerTop: { flex: 1.1 },
  welcomeSpacerBottom: { flex: 1 },
  welcomeTextBlock: { marginBottom: spacing.xl },
  welcomeSubtitle: { ...type.bodyBold, color: colors.deepPlum, fontSize: 17, lineHeight: 24, marginTop: spacing.sm },
  welcomeTopBlob: { position: 'absolute', top: -60, right: -50 },
  welcomeLeftBlob: { position: 'absolute', top: '32%', left: -55 },
  welcomeBottomBlob: { position: 'absolute', bottom: -210, left: '50%', marginLeft: -130 },
  welcomeContours: { top: 40, left: -20, right: 0, bottom: 0 },

  stepSpacerTop: { height: spacing.xxl },
  stepBlob: { position: 'absolute', top: -40, right: -40, opacity: 0.9 },
  stepContours: { top: 0, left: -40, right: 0, bottom: 0 },
  input: {
    backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.md,
    color: colors.textPrimary, padding: 16, fontSize: 17, fontWeight: '600', marginTop: spacing.lg,
  },
  continueButton: { marginTop: spacing.xl },
  genderRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.lg },
  error: { color: colors.negative, fontSize: 13, marginTop: spacing.md },
});
