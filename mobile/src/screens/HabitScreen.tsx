import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator, Platform, ScrollView, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import DateTimePicker from '@react-native-community/datetimepicker';
import { api } from '../api';
import {
  cancelDailyAlarm, cancelSnoozeAlarm, dismissPresentedAlarmNotifications, scheduleDailyAlarm, scheduleSnoozeAlarm,
} from '../notifications';
import { speakCoachMessage } from '../speech';
import { playAlarmSequence, stopAlarmSequence } from '../audioLifecycle';
import { fmtTime, to24h, parseTimeToDate } from '../format';
import { GhostButton, PrimaryButton } from '../components/Button';
import { colors, radii, shadow, spacing, treeStageColors, treeStageLabels, type } from '../theme';
import type { CurrentIntervention, FeedbackReason, Habit, HabitDay, TreeStage } from '../types';

const REASONS: { value: FeedbackReason; label: string }[] = [
  { value: 'too_tired', label: '😴 Too tired' },
  { value: 'no_time', label: '🕐 No time' },
  { value: 'forgot', label: '😐 Forgot' },
  { value: 'something_came_up', label: '🚫 Something came up' },
  { value: 'not_feeling_it', label: "😩 Didn't feel like it" },
  { value: 'other', label: '✍️ Other' },
];

/** No fourth snooze: after the 3rd snooze on the same day's task, the day
 * is automatically marked MISSED instead of offering another snooze. */
const MAX_SNOOZES_PER_DAY = 3;
const SNOOZE_CHOICES_MINUTES = [5, 10, 15] as const;

interface Props {
  habitId: number;
  /** Bumped by App.tsx whenever a notification tap should re-open this exact
   * habit's coach moment (cold start or foreground tap) — triggers a fresh
   * fetch + speak. */
  openCoachSignal: number;
  onHabitChanged: (habit: Habit) => void;
  onOpenChallenges: () => void;
  /** Called the moment a nonzero openCoachSignal has been acted on. Resets
   * it to 0 in the PARENT (App.tsx) — necessary because this screen can
   * unmount/remount (e.g. via My Challenges) while App.tsx's signal value
   * persists; without this, a stale signal from an earlier real alarm tap
   * would "replay" and wrongly re-ring for whichever habit mounts next. */
  onSignalConsumed: () => void;
}

/** "Day of week" doesn't exist per HabitDay in the data model (all 21 rows
 * are pre-created at habit-creation time — see backend JourneyService — so
 * there is no real calendar date stored per day_number). Anything below
 * that looks like "this week's stats" is therefore computed from the
 * trailing REPORTED (non-pending) days in day_number order, not real
 * calendar dates — honest given what the backend actually exposes, rather
 * than fabricating a day-of-week mapping that doesn't exist. */
function reportedDays(days: HabitDay[]): HabitDay[] {
  return days.filter((d) => d.status !== 'pending');
}
function currentStreak(days: HabitDay[]): number {
  const reported = reportedDays(days);
  let streak = 0;
  for (let i = reported.length - 1; i >= 0; i--) {
    if (reported[i].status === 'done') streak++;
    else break;
  }
  return streak;
}
function recentWindow(days: HabitDay[], count = 7): HabitDay[] {
  return reportedDays(days).slice(-count);
}

function greetingWord(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good Morning';
  if (hour < 17) return 'Good Afternoon';
  return 'Good Evening';
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export default function HabitScreen({
  habitId, openCoachSignal, onHabitChanged, onOpenChallenges, onSignalConsumed,
}: Props) {
  const [habit, setHabit] = useState<Habit | null>(null);
  const [days, setDays] = useState<HabitDay[]>([]);
  const [current, setCurrent] = useState<CurrentIntervention | null>(null);
  const [response, setResponse] = useState<{
    text: string; kind: string; treeHealth?: number; treeStage?: TreeStage; treeComeback?: boolean;
  } | null>(null);
  // Only "missed" asks a reason now — snooze uses an explicit duration
  // picker instead (see snoozePromptOpen).
  const [pendingAction, setPendingAction] = useState<'missed' | null>(null);
  const [selectedReason, setSelectedReason] = useState<FeedbackReason | null>(null);
  const [snoozePromptOpen, setSnoozePromptOpen] = useState(false);
  const [showTimeEditor, setShowTimeEditor] = useState(false);
  const [pickedTime, setPickedTime] = useState(new Date());
  const [showPicker, setShowPicker] = useState(false);
  const [busy, setBusy] = useState(false);
  // True exactly while the app is treating this as "the alarm is ringing" —
  // set by a real scheduled-notification tap, cleared by
  // Stop Alarm, Snooze, Done, or Missed. The chime plays once and stops
  // itself before speech starts (see audioLifecycle.ts) — this flag is
  // about which UI/buttons to show, not about audio still playing.
  const [ringing, setRinging] = useState(false);
  // Header greeting only — see reportedDays()'s javadoc on why this screen
  // otherwise avoids real-calendar-date claims. Purely decorative/display,
  // not tied to any habit-day business logic.
  const [profileName, setProfileName] = useState<string | null>(null);
  const [monthOffset, setMonthOffset] = useState(0);

  useEffect(() => {
    api.getProfile().then((p) => setProfileName(p?.name ?? null)).catch(() => setProfileName(null));
  }, []);

  // Full-month grid, matching the reference design. Still visual-only for
  // anything beyond "today" — see reportedDays()'s javadoc: HabitDay has no
  // real calendar date per day_number, so only today can honestly be
  // distinguished against real data. Leading/trailing days from adjacent
  // months are included (dimmed) to fill out full weeks, same as any
  // standard calendar grid.
  const monthAnchor = useMemo(() => {
    const d = new Date();
    d.setDate(1);
    d.setHours(0, 0, 0, 0);
    d.setMonth(d.getMonth() + monthOffset);
    return d;
  }, [monthOffset]);

  const monthLabel = monthAnchor.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

  const monthGrid = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const gridStart = new Date(monthAnchor);
    gridStart.setDate(gridStart.getDate() - gridStart.getDay());
    const daysInMonth = new Date(monthAnchor.getFullYear(), monthAnchor.getMonth() + 1, 0).getDate();
    const totalCells = Math.ceil((monthAnchor.getDay() + daysInMonth) / 7) * 7;
    return Array.from({ length: totalCells }, (_, i) => {
      const d = new Date(gridStart);
      d.setDate(d.getDate() + i);
      return {
        key: d.toISOString(),
        date: d.getDate(),
        inMonth: d.getMonth() === monthAnchor.getMonth(),
        isToday: d.getTime() === today.getTime(),
      };
    });
  }, [monthAnchor]);

  const monthWeeks = useMemo(() => {
    const weeks: (typeof monthGrid)[] = [];
    for (let i = 0; i < monthGrid.length; i += 7) weeks.push(monthGrid.slice(i, i + 7));
    return weeks;
  }, [monthGrid]);

  const load = useCallback(async (announce = false) => {
    const detail = await api.getHabitDetail(habitId);
    const cur = await api.getCurrent(habitId);
    setHabit(detail.habit);
    setDays(detail.days);
    setCurrent(cur);
    onHabitChanged(detail.habit);
    if (announce && !cur.finished && cur.intervention_text) {
      setRinging(true);
      playAlarmSequence(cur.intervention_text); // chime, THEN (after it fully stops) speech — never both
    }
    return cur;
  }, [habitId, onHabitChanged]);

  /** STOP ALARM: silences audio and dismisses the shade notification only —
   * deliberately does NOT call the backend at all. The day stays exactly as
   * it was (still pending, no action recorded), and the permanent daily
   * schedule is completely untouched. */
  function stopAlarm() {
    stopAlarmSequence();
    setRinging(false);
    dismissPresentedAlarmNotifications(habitId);
  }

  function openMissedFeedback() {
    stopAlarmSequence();
    setRinging(false);
    setPendingAction('missed');
    setSelectedReason(null);
  }

  function openSnoozeChoices() {
    stopAlarmSequence();
    setRinging(false);
    setSnoozePromptOpen(true);
  }

  useEffect(() => {
    load(false);
  }, [load]);

  // The permanent daily OS alarm always tracks the habit's saved time —
  // scheduling here (not on a manual button) means it's re-armed the moment
  // the schedule changes, and cancelled/re-armed correctly across app restarts.
  useEffect(() => {
    if (habit && habit.status === 'active') {
      scheduleDailyAlarm(habit);
    } else if (habit) {
      cancelDailyAlarm(habit.id);
    }
  }, [habit?.time_of_day, habit?.status, habit?.id]);

  // A notification tap (foreground listener or cold start) bumps this —
  // re-fetch (fresh Strands generation) and speak.
  // Immediately reported back as consumed (see onSignalConsumed) so it
  // can never fire twice, even across this screen unmounting/remounting.
  useEffect(() => {
    if (openCoachSignal) {
      load(true);
      onSignalConsumed();
    }
  }, [openCoachSignal, load, onSignalConsumed]);

  /** DONE or MISSED — both finalize the day, so both must guarantee
   * tomorrow's daily alarm exists at the habit's current scheduled time
   * (Priority 3: "after today's task is DONE or MISSED, automatically
   * schedule tomorrow's alarm"). scheduleDailyAlarm computes the next
   * occurrence of time_of_day, which is naturally tomorrow once today's
   * slot has passed or been resolved — cancel-then-set under the same
   * identifier means this is always safe to call again, never creates a
   * duplicate. */
  async function act(action: 'done' | 'missed', reason: FeedbackReason | null = null) {
    // Whatever the user is choosing, that choice itself is the
    // acknowledgement — silence any ringing chime/speech first.
    stopAlarmSequence();
    setRinging(false);
    setBusy(true);
    try {
      const res = await api.act(habitId, action, reason);
      setPendingAction(null);
      setSelectedReason(null);
      if (habit) await scheduleDailyAlarm(habit);

      if (res.response_text) {
        setResponse({
          text: res.response_text, kind: res.response_kind || action,
          treeHealth: res.tree_health, treeStage: res.tree_stage, treeComeback: res.tree_comeback,
        });
        speakCoachMessage(res.response_text);
      } else {
        await load(false);
      }
    } finally {
      setBusy(false);
    }
  }

  /**
   * Explicit 5/10/15-minute snooze choice. Every snooze increments the
   * day's snooze_count server-side; after the 3rd, there is no fourth —
   * the day is automatically marked MISSED instead (Priority 3).
   *
   * Timing (Priority 2): the snooze alarm is armed via AlarmManager the
   * INSTANT this function runs — before either network call below — using
   * Date.now() at that moment as the anchor (see scheduleSnoozeAlarm). It
   * is never derived from the original alarm time or the permanent daily
   * time_of_day, and its precision must not depend on backend latency. If
   * this turns out to be the disallowed 3rd snooze, the alarm just armed
   * is cancelled immediately below and the day is finalized as missed
   * instead — the brief optimistic schedule is never allowed to fire.
   */
  async function handleSnoozeChoice(minutes: number) {
    stopAlarmSequence();
    setRinging(false);
    setSnoozePromptOpen(false);
    setBusy(true);
    try {
      if (habit) await scheduleSnoozeAlarm(habit, minutes);

      await api.act(habitId, 'snoozed');
      const detail = await api.getHabitDetail(habitId);
      const dayNumber = current?.day_number ?? 1;
      const snoozeCount = detail.days.find((d) => d.day_number === dayNumber)?.snooze_count ?? 0;

      if (snoozeCount >= MAX_SNOOZES_PER_DAY) {
        await cancelSnoozeAlarm(habitId);
        const res = await api.act(habitId, 'missed');
        if (habit) await scheduleDailyAlarm(habit);
        if (res.response_text) {
          setResponse({
            text: res.response_text, kind: res.response_kind || 'missed',
            treeHealth: res.tree_health, treeStage: res.tree_stage, treeComeback: res.tree_comeback,
          });
          speakCoachMessage(res.response_text);
        } else {
          await load(false);
        }
        return;
      }

      // Do NOT re-ring now: the next real ring must come from the native
      // snooze alarm firing `minutes` from now (AlarmReceiver -> App.tsx's
      // addAlarmLaunchListener/getInitialAlarm -> openCoachSignal -> this
      // screen's own load(true) effect). Calling load(true) here would ring
      // immediately regardless of the chosen duration — this is exactly the
      // "snooze fires immediately" bug; only silently refresh state.
      await load(false);
    } finally {
      setBusy(false);
    }
  }

  async function dismissResponse() {
    setResponse(null);
    await load(false);
  }

  async function saveTime(newTime: string) {
    setBusy(true);
    try {
      const updated = await api.updateSchedule(habitId, newTime);
      setHabit(updated);
      onHabitChanged(updated);
      setShowTimeEditor(false);
      await load(false);
    } finally {
      setBusy(false);
    }
  }

  async function continueJourney() {
    const next = await api.continueHabit(habitId);
    onHabitChanged(next);
  }
  async function stopJourney() {
    await cancelDailyAlarm(habitId);
    await cancelSnoozeAlarm(habitId);
    await api.stopHabit(habitId);
    await load(false);
  }

  if (!habit || !current) {
    return (
      <View style={styles.page}>
        <View style={styles.center}>
          <ActivityIndicator color={colors.primary} />
        </View>
      </View>
    );
  }

  if (response) {
    const icon = response.kind.startsWith('completion') ? '🎉' : '💛';
    return (
      <View style={styles.page}>
        <View style={styles.responseWrap}>
          <View style={styles.responseCard}>
            <Text style={styles.responseIcon}>{icon}</Text>
            <Text style={styles.responseText}>{response.text}</Text>
            {response.treeStage != null && (
              <Text style={styles.treeText}>
                🌳 {treeStageLabels[response.treeStage]} · {response.treeHealth}%{response.treeComeback ? '  · comeback!' : ''}
              </Text>
            )}
            <PrimaryButton label="Continue" onPress={dismissResponse} style={styles.responseButton} />
          </View>
        </View>
      </View>
    );
  }

  if (current.finished) {
    return (
      <View style={styles.page}>
        <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
          <View style={styles.card}>
            <Text style={type.h1} numberOfLines={1}>{habit.emoji} {habit.name}</Text>
            <View style={styles.finishedTreeWrap}>
              <Text style={styles.finishedTreeEmoji}>🌳</Text>
              <Text style={styles.treeText}>{treeStageLabels[habit.tree_stage]} · {habit.tree_health}%</Text>
            </View>
            <Text style={styles.summaryText}>{current.summary}</Text>
            <PrimaryButton label="Start another 21 days" onPress={continueJourney} style={styles.primaryButtonSpacing} />
            <GhostButton label="Stop here" onPress={stopJourney} />
          </View>
          <TouchableOpacity style={styles.goBackButton} onPress={onOpenChallenges}>
            <Text style={styles.goBackButtonText}>← Go back</Text>
          </TouchableOpacity>
        </ScrollView>
      </View>
    );
  }

  const recent = recentWindow(days);
  const recentDoneCount = recent.filter((d) => d.status === 'done').length;

  return (
    <View style={styles.page}>
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* Header: greeting only. The reference shows notification/profile
            icons, but neither has a real function (no notification inbox,
            no profile-editing screen) — removed rather than kept as
            decorative-only controls (see CLAUDE_CONTEXT.md's UI redesign
            notes). Android's own notification infrastructure for the alarm
            is untouched; this only removes an in-app bell button. */}
        <View style={styles.headerRow}>
          <Text style={styles.greetingSmall}>{greetingWord()},</Text>
          <Text style={styles.greetingName} numberOfLines={1}>{profileName ?? 'there'}!</Text>
          <View style={styles.greetingUnderline} />
          <Text style={styles.greetingSubtitle}>Small steps, big results 💪</Text>
        </View>

        <View style={styles.treePill}>
          <View style={styles.treePillIconWrap}>
            <Text style={styles.treePillIconText}>🌳</Text>
          </View>
          <View style={styles.treePillTextWrap}>
            <Text style={styles.treePillLabel}>Tree: {treeStageLabels[habit.tree_stage].toLowerCase()}</Text>
            <Text style={styles.treePillPercent}>{habit.tree_health}%</Text>
            <View style={styles.treePillTrack}>
              <View style={[styles.treePillFill, { width: `${habit.tree_health}%`, backgroundColor: treeStageColors[habit.tree_stage] }]} />
            </View>
          </View>
        </View>

        {/* Full-month calendar — "<"/">" step the displayed month client-side,
            "Today" resets it. No per-day historical data is bound to these
            dates (see monthGrid's javadoc above) — only today is ever
            actually highlighted against real data. */}
        <View style={styles.card}>
          <View style={styles.calendarHeadRow}>
            <Text style={styles.calendarMonth}>{monthLabel}</Text>
            <View style={styles.calendarNavRow}>
              <TouchableOpacity onPress={() => setMonthOffset((o) => o - 1)} hitSlop={8}>
                <Text style={styles.calendarNavArrow}>‹</Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={() => setMonthOffset((o) => o + 1)} hitSlop={8}>
                <Text style={styles.calendarNavArrow}>›</Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={() => setMonthOffset(0)} style={styles.todayPill}>
                <Text style={styles.todayPillText}>Today</Text>
              </TouchableOpacity>
            </View>
          </View>
          <View style={styles.weekRow}>
            {WEEKDAY_LABELS.map((label) => <Text key={label} style={styles.weekDayLabel}>{label}</Text>)}
          </View>
          {monthWeeks.map((week, i) => (
            <View key={i} style={styles.weekRow}>
              {week.map((d) => (
                <View key={d.key} style={[styles.dateCircle, !d.inMonth && styles.dateCircleOutMonth, d.isToday && styles.dateCircleToday]}>
                  <Text style={[styles.dateCircleText, !d.inMonth && styles.dateCircleTextOutMonth, d.isToday && styles.dateCircleTextToday]}>
                    {d.date}
                  </Text>
                </View>
              ))}
            </View>
          ))}
        </View>

        <View style={styles.habitCard}>
          <View style={styles.habitHeadRow}>
            <Text style={styles.habitEmoji}>{habit.emoji}</Text>
            <Text style={styles.habitName} numberOfLines={2}>{habit.name}</Text>
          </View>
          <View style={styles.habitMetaRow}>
            <View style={styles.habitMetaTextWrap}>
              <Text style={styles.habitMetaItem}>📅 Day {current.day_number} of {current.total_days}</Text>
              <Text style={styles.habitMetaItem}>🔁 Every day</Text>
              <Text style={styles.habitMetaItem}>🕐 {fmtTime(habit.time_of_day)}</Text>
            </View>
            <TouchableOpacity
              style={styles.changeTimeButton}
              onPress={() => { setPickedTime(parseTimeToDate(habit.time_of_day)); setShowTimeEditor(true); setShowPicker(Platform.OS === 'ios'); }}
            >
              <Text style={styles.changeTimeButtonText}>✏️ Change time</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.dots}>
            {days.map((d) => {
              let color: string = 'transparent';
              let border: string = colors.surfaceCard;
              if (d.status === 'done') { color = colors.positive; border = colors.positive; }
              else if (d.status === 'missed') { border = colors.negative; }
              else if (d.day_number === current.day_number) { color = colors.primary; border = colors.primary; }
              return <View key={d.day_number} style={[styles.dot, { backgroundColor: color, borderColor: border }]} />;
            })}
          </View>

          {ringing && (
            <View style={styles.alarmRingBanner}>
              <Text style={styles.alarmRingTitle}>🔔 HABIT ALARM</Text>
              <Text style={styles.alarmRingSubtitle}>{habit.emoji} {habit.name} time</Text>
              <View style={styles.rowGap}>
                <PrimaryButton label="STOP ALARM" onPress={stopAlarm} style={styles.stopAlarmButton} />
                <TouchableOpacity style={styles.snoozeAlarmButton} onPress={openSnoozeChoices}>
                  <Text style={styles.snoozeAlarmButtonText}>SNOOZE</Text>
                </TouchableOpacity>
              </View>
            </View>
          )}

          {showTimeEditor && (
            <View style={styles.timeEditorCard}>
              <Text style={styles.timeEditorTitle}>When should we{'\n'}remind you?</Text>
              <Text style={styles.timeEditorBigTime}>{fmtTime(to24h(pickedTime))}</Text>
              {Platform.OS === 'android' && !showPicker && (
                <TouchableOpacity style={styles.timeButton} onPress={() => setShowPicker(true)}>
                  <Text style={styles.timeButtonText}>Choose time</Text>
                </TouchableOpacity>
              )}
              {showPicker && (
                <DateTimePicker
                  value={pickedTime}
                  mode="time"
                  display={Platform.OS === 'ios' ? 'spinner' : 'default'}
                  onChange={(_e, date) => { if (Platform.OS === 'android') setShowPicker(false); if (date) setPickedTime(date); }}
                />
              )}
              <PrimaryButton label="Confirm time" onPress={() => saveTime(to24h(pickedTime))} loading={busy} style={styles.timeEditorConfirm} />
              <GhostButton label="Cancel" onPress={() => setShowTimeEditor(false)} />
            </View>
          )}

          <View style={styles.voiceLine}>
            <Text style={styles.voiceText}>🔊 {current.intervention_text}</Text>
            <Text style={styles.strategyTag}>strategy: {(current.strategy || '').replace('_', ' ')}</Text>
          </View>

          {current.strategy === 'reschedule' && current.suggested_time && (
            <View style={styles.rescheduleBanner}>
              <Text style={styles.rescheduleTitle}>Would you like to update the schedule?</Text>
              <View style={styles.rowWrap}>
                <TouchableOpacity style={styles.chipButton} onPress={() => setResponse(null)}>
                  <Text style={styles.chipButtonText}>Keep {fmtTime(habit.time_of_day)}</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.chipButtonPrimary} onPress={() => saveTime(current.suggested_time!)}>
                  <Text style={styles.chipButtonPrimaryText}>Change to {fmtTime(current.suggested_time)}</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.chipButton}
                  onPress={() => { setPickedTime(parseTimeToDate(habit.time_of_day)); setShowTimeEditor(true); setShowPicker(Platform.OS === 'ios'); }}
                >
                  <Text style={styles.chipButtonText}>Choose another time</Text>
                </TouchableOpacity>
              </View>
            </View>
          )}

          {!pendingAction && !snoozePromptOpen && (
            <View style={styles.actionsRow}>
              <TouchableOpacity style={styles.doneButton} onPress={() => act('done')} disabled={busy}>
                <Text style={styles.doneButtonText}>✓ Done</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.snoozeButton} onPress={openSnoozeChoices} disabled={busy}>
                <Text style={styles.snoozeButtonText}>⏰ Snooze</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.missedButton} onPress={openMissedFeedback} disabled={busy}>
                <Text style={styles.missedButtonText}>✕ Missed</Text>
              </TouchableOpacity>
            </View>
          )}

          {snoozePromptOpen && (
            <View style={styles.feedbackBox}>
              <Text style={styles.feedbackLabel}>Snooze for how long?</Text>
              <View style={styles.rowWrap}>
                {SNOOZE_CHOICES_MINUTES.map((minutes) => (
                  <TouchableOpacity key={minutes} style={styles.chipButton} onPress={() => handleSnoozeChoice(minutes)} disabled={busy}>
                    <Text style={styles.chipButtonText}>{minutes} min</Text>
                  </TouchableOpacity>
                ))}
              </View>
              <TouchableOpacity style={styles.snoozeButton} onPress={() => setSnoozePromptOpen(false)} disabled={busy}>
                <Text style={styles.snoozeButtonText}>Cancel</Text>
              </TouchableOpacity>
            </View>
          )}

          {pendingAction && (
            <View style={styles.feedbackBox}>
              <Text style={styles.feedbackLabel}>What happened? (optional)</Text>
              <View style={styles.rowWrap}>
                {REASONS.map((r) => (
                  <TouchableOpacity
                    key={r.value}
                    style={[styles.chipButton, selectedReason === r.value && styles.chipButtonSelected]}
                    onPress={() => setSelectedReason(r.value)}
                  >
                    <Text style={styles.chipButtonText}>{r.label}</Text>
                  </TouchableOpacity>
                ))}
              </View>
              <View style={styles.rowGap}>
                <TouchableOpacity style={styles.doneButton} onPress={() => act(pendingAction, selectedReason)} disabled={busy}>
                  <Text style={styles.doneButtonText}>Confirm</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.snoozeButton} onPress={() => { setPendingAction(null); setSelectedReason(null); }}>
                  <Text style={styles.snoozeButtonText}>Cancel</Text>
                </TouchableOpacity>
              </View>
            </View>
          )}
        </View>

        <View style={styles.statsRow}>
          <View style={[styles.card, styles.statsCardLeft]}>
            <Text style={styles.statsCardTitle}>📊 Your Progress</Text>
            <Text style={styles.statsCardSubtitle}>Recent days</Text>
            {recent.length === 0 ? (
              <Text style={styles.statsEmpty}>Nothing recorded yet.</Text>
            ) : (
              <View style={styles.barChartRow}>
                {recent.map((d) => (
                  <View key={d.day_number} style={styles.barCol}>
                    <View style={styles.barTrack}>
                      <View style={[styles.bar, { height: d.status === 'done' ? '100%' : '8%' }]} />
                    </View>
                    <Text style={styles.barLabel}>{d.day_number}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>

          <View style={[styles.card, styles.statsCardRight]}>
            <Text style={styles.statsCardTitle}>🔥 Streak & Stats</Text>
            <View style={styles.statChip}>
              <Text style={styles.statChipIcon}>🔥</Text>
              <View>
                <Text style={styles.statChipValue}>{currentStreak(days)}</Text>
                <Text style={styles.statChipLabel}>Day streak</Text>
              </View>
            </View>
            <View style={styles.statChip}>
              <Text style={styles.statChipIcon}>🎯</Text>
              <View>
                <Text style={styles.statChipValue}>{current.total_days}</Text>
                <Text style={styles.statChipLabel}>Days goal</Text>
              </View>
            </View>
            {/* Tree health is deliberately NOT repeated here — it's already
                shown in the tree pill above; showing it twice would be the
                exact duplication the new design explicitly calls out to avoid. */}
            <View style={styles.statChip}>
              <Text style={styles.statChipIcon}>📊</Text>
              <View>
                <Text style={styles.statChipValue}>{recentDoneCount}/{recent.length}</Text>
                <Text style={styles.statChipLabel}>Recent days</Text>
              </View>
            </View>
          </View>
        </View>

        <TouchableOpacity style={styles.goBackButton} onPress={onOpenChallenges}>
          <Text style={styles.goBackButtonText}>← Go back</Text>
        </TouchableOpacity>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.habitPageBg },
  scrollContent: { padding: spacing.md, paddingBottom: spacing.xl },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },

  headerRow: { marginBottom: spacing.md },
  greetingSmall: { ...type.h2, fontSize: 18 },
  greetingName: { ...type.display, fontSize: 28, marginTop: -4 },
  greetingUnderline: { width: 60, height: 3, backgroundColor: colors.textHeading, borderRadius: 2, marginTop: 4, marginBottom: spacing.xs },
  greetingSubtitle: { ...type.small, fontSize: 13.5 },

  treePill: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surfaceCard, borderRadius: radii.lg,
    padding: spacing.sm, marginBottom: spacing.md, ...shadow.soft,
  },
  treePillIconWrap: { width: 44, height: 44, borderRadius: 22, backgroundColor: colors.surfaceAlt, alignItems: 'center', justifyContent: 'center', marginRight: spacing.sm },
  treePillIconText: { fontSize: 22 },
  treePillTextWrap: { flex: 1 },
  treePillLabel: { ...type.bodyBold, fontSize: 14, textTransform: 'capitalize' },
  treePillPercent: { ...type.h1, fontSize: 20, marginTop: 1 },
  treePillTrack: { height: 8, borderRadius: 4, backgroundColor: colors.surfaceAlt, marginTop: spacing.xs, overflow: 'hidden' },
  treePillFill: { height: '100%', borderRadius: 4 },

  card: { backgroundColor: colors.surfaceCard, borderRadius: radii.lg, padding: spacing.md, marginBottom: spacing.md, ...shadow.soft },
  calendarHeadRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.sm },
  calendarMonth: { ...type.bodyBold, fontSize: 16 },
  calendarNavRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  calendarNavArrow: { fontSize: 20, color: colors.textSecondary, fontWeight: '700', paddingHorizontal: 2 },
  todayPill: { backgroundColor: colors.surfaceAlt, borderRadius: radii.pill, paddingVertical: 5, paddingHorizontal: 12 },
  todayPillText: { ...type.tiny, fontWeight: '700' },
  weekRow: { flexDirection: 'row', marginTop: spacing.xs },
  weekDayLabel: { ...type.tiny, flex: 1, textAlign: 'center' },
  dateCircle: {
    flex: 1, aspectRatio: 1, marginHorizontal: 2, borderRadius: 999,
    alignItems: 'center', justifyContent: 'center', backgroundColor: 'transparent',
  },
  dateCircleOutMonth: { opacity: 0.35 },
  dateCircleToday: { backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.textHeading },
  dateCircleText: { ...type.small, fontWeight: '600', color: colors.textPrimary, fontSize: 12.5 },
  dateCircleTextOutMonth: { color: colors.textMuted },
  dateCircleTextToday: { fontWeight: '800' },

  habitCard: { backgroundColor: colors.accentGold, borderRadius: radii.lg, padding: spacing.md, marginBottom: spacing.md, ...shadow.card },
  habitHeadRow: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.xs },
  habitEmoji: { fontSize: 26, marginRight: spacing.sm },
  habitName: { ...type.h1, fontSize: 20, flex: 1 },
  habitMetaRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm, marginBottom: spacing.md },
  habitMetaTextWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md, flexShrink: 1 },
  habitMetaItem: { ...type.small, color: colors.onAccentGold, fontWeight: '700', fontSize: 12.5 },
  changeTimeButton: { borderWidth: 1.5, borderColor: colors.textHeading, borderRadius: radii.pill, paddingVertical: 6, paddingHorizontal: 12, backgroundColor: colors.surfaceCard },
  changeTimeButtonText: { color: colors.textPrimary, fontSize: 12, fontWeight: '700' },

  dots: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: spacing.md },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 1.5 },

  voiceLine: { backgroundColor: colors.surfaceCard, borderLeftWidth: 4, borderLeftColor: colors.primary, borderRadius: radii.md, padding: spacing.md, marginBottom: spacing.md },
  voiceText: { color: colors.textPrimary, fontSize: 15.5, fontWeight: '500', lineHeight: 22 },
  strategyTag: { ...type.tiny, marginTop: spacing.sm },

  rescheduleBanner: { backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.primary, borderRadius: radii.md, padding: spacing.md, marginBottom: spacing.md },
  rescheduleTitle: { ...type.small, fontWeight: '700', marginBottom: spacing.sm },

  alarmRingBanner: { backgroundColor: colors.deepPlum, borderRadius: radii.lg, padding: spacing.lg, marginBottom: spacing.md, alignItems: 'center', ...shadow.card },
  alarmRingTitle: { color: colors.accentGold, fontSize: 20, fontWeight: '800', letterSpacing: 0.5, marginBottom: 6 },
  alarmRingSubtitle: { color: colors.surface, fontSize: 15, marginBottom: spacing.md },
  stopAlarmButton: { flex: 1, paddingVertical: 14 },
  snoozeAlarmButton: { flex: 1, backgroundColor: 'transparent', borderWidth: 1.5, borderColor: colors.surface, borderRadius: radii.pill, paddingVertical: 14, alignItems: 'center' },
  snoozeAlarmButtonText: { color: colors.surface, fontWeight: '800', fontSize: 14, letterSpacing: 0.5 },

  actionsRow: { flexDirection: 'row', gap: spacing.sm },
  doneButton: { flex: 1, backgroundColor: colors.positive, borderRadius: radii.md, paddingVertical: 13, alignItems: 'center' },
  doneButtonText: { color: colors.onPositive, fontWeight: '700' },
  snoozeButton: { flex: 1, backgroundColor: colors.surfaceCard, borderRadius: radii.md, paddingVertical: 13, alignItems: 'center' },
  snoozeButtonText: { color: colors.textPrimary, fontWeight: '700' },
  missedButton: { flex: 1, backgroundColor: colors.negativeBg, borderRadius: radii.md, paddingVertical: 13, alignItems: 'center' },
  missedButtonText: { color: colors.negative, fontWeight: '700' },

  feedbackBox: { borderTopWidth: 1, borderTopColor: 'rgba(59,42,46,0.12)', paddingTop: spacing.md, marginTop: spacing.xs },
  feedbackLabel: { ...type.small, color: colors.onAccentGold, fontWeight: '700', marginBottom: spacing.sm },
  rowWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginBottom: spacing.md },
  rowGap: { flexDirection: 'row', gap: spacing.sm },
  chipButton: { backgroundColor: colors.surfaceCard, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.pill, paddingVertical: 8, paddingHorizontal: 14 },
  chipButtonSelected: { borderColor: colors.primary },
  chipButtonText: { color: colors.textPrimary, fontSize: 12.5, fontWeight: '600' },
  chipButtonPrimary: { backgroundColor: colors.positive, borderRadius: radii.pill, paddingVertical: 8, paddingHorizontal: 14 },
  chipButtonPrimaryText: { color: colors.onPositive, fontSize: 12.5, fontWeight: '700' },

  timeEditorCard: { backgroundColor: colors.surfaceCard, borderRadius: radii.lg, padding: spacing.lg, marginBottom: spacing.md, alignItems: 'center', ...shadow.soft },
  timeEditorTitle: { ...type.h1, fontSize: 20, textAlign: 'center', marginBottom: spacing.md },
  timeEditorBigTime: { color: colors.primary, fontSize: 40, fontWeight: '800', marginBottom: spacing.md },
  timeButton: { backgroundColor: colors.surfaceAlt, borderWidth: 1.5, borderColor: colors.border, borderRadius: radii.pill, paddingVertical: spacing.sm, paddingHorizontal: spacing.lg, alignItems: 'center', marginBottom: spacing.sm },
  timeButtonText: { color: colors.textPrimary, fontSize: 15, fontWeight: '700' },
  timeEditorConfirm: { alignSelf: 'stretch', marginTop: spacing.sm, marginBottom: spacing.xs },

  statsRow: { flexDirection: 'row', gap: spacing.sm },
  statsCardLeft: { flex: 1.1 },
  statsCardRight: { flex: 1 },
  statsCardTitle: { ...type.bodyBold, fontSize: 14.5, marginBottom: 2 },
  statsCardSubtitle: { ...type.tiny, marginBottom: spacing.sm },
  statsEmpty: { ...type.tiny, marginTop: spacing.sm },
  barChartRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 4, height: 90 },
  barCol: { flex: 1, alignItems: 'center' },
  barTrack: { width: '100%', height: 70, justifyContent: 'flex-end' },
  bar: { width: '100%', backgroundColor: colors.accentGold, borderRadius: 4 },
  barLabel: { ...type.tiny, fontSize: 9.5, marginTop: 3 },

  statChip: { flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surfaceAlt, borderRadius: radii.md, padding: spacing.sm, marginBottom: spacing.xs, gap: spacing.xs },
  statChipIcon: { fontSize: 16 },
  statChipValue: { ...type.bodyBold, fontSize: 15 },
  statChipLabel: { ...type.tiny, fontSize: 10.5 },

  goBackButton: { alignSelf: 'center', borderWidth: 1.5, borderColor: colors.textHeading, borderRadius: radii.pill, paddingVertical: 12, paddingHorizontal: 28, marginTop: spacing.sm },
  goBackButtonText: { color: colors.textPrimary, fontWeight: '700', fontSize: 14.5 },

  finishedTreeWrap: { alignItems: 'center', marginVertical: spacing.lg },
  finishedTreeEmoji: { fontSize: 56, marginBottom: spacing.xs },
  summaryText: { ...type.body, fontSize: 16.5, lineHeight: 24, marginBottom: spacing.md },
  primaryButtonSpacing: { marginTop: spacing.md, marginBottom: spacing.xs },

  responseWrap: { flex: 1, justifyContent: 'center', padding: spacing.lg },
  responseCard: { backgroundColor: colors.surfaceCard, borderRadius: radii.lg, padding: spacing.xl, alignItems: 'center', ...shadow.card },
  responseIcon: { fontSize: 34, marginBottom: spacing.md },
  responseText: { color: colors.textPrimary, fontSize: 18, fontWeight: '600', textAlign: 'center', lineHeight: 26, marginBottom: spacing.md },
  treeText: { color: colors.textSecondary, fontSize: 13, fontWeight: '700', marginBottom: spacing.lg },
  responseButton: { alignSelf: 'stretch' },
});
