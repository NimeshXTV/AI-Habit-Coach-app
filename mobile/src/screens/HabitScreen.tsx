import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, ScrollView, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { api } from '../api';
import { drainNativeEvents } from '../localStore';
import {
  cancelDailyAlarm, cancelSnoozeAlarm, dismissPresentedAlarmNotifications, scheduleDailyAlarm, scheduleSnoozeAlarm,
} from '../notifications';
import { speakCoachMessage } from '../speech';
import { stopAlarmSequence } from '../audioLifecycle';
import { fmtTime } from '../format';
import { GhostButton, PrimaryButton } from '../components/Button';
import TimePickerModal from '../components/TimePickerModal';
import DayStatusEditModal, { type EditableDayStatus } from '../components/DayStatusEditModal';
import AdvisorScreen from './AdvisorScreen';
import { colors, radii, shadow, spacing, type } from '../theme';
import type { CurrentIntervention, FeedbackReason, Habit, HabitDay } from '../types';

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

/** Actions (done/missed/snooze/schedule/continue/stop) all write to Spring
 * Boot and, unlike the read paths in api.ts, are deliberately NOT queued
 * for later sync when it's unreachable — that's a materially bigger
 * feature than "the app must still open offline" and wasn't asked for.
 * Instead they fail fast (see api.ts's request timeout) and surface this,
 * so the user knows to retry once back online rather than the button
 * silently doing nothing or the screen hanging on `busy`. */
function reportActionOffline() {
  Alert.alert("Couldn't reach the server", 'Check your connection and try again.');
}

function greetingWord(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good Morning';
  if (hour < 17) return 'Good Afternoon';
  return 'Good Evening';
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** Stable per-calendar-day identity (local time), used to line up a grid
 * cell with a mapped HabitDay below. */
function dateKey(d: Date): string {
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

/**
 * Maps each resolved (done/missed) HabitDay to the real calendar date it
 * falls on: day 1 is the habit's creation day by construction (see backend
 * JourneyService.initializeDays), so day N is deterministically
 * `habit.created_at + (N-1) days` in local time. This is a derivation from
 * real fields already on the wire (`habit.created_at`, `day.status`), not
 * invented data — HabitDay itself still carries no calendar date of its
 * own, this is just the app computing what that date must be from the
 * habit's actual start date.
 */
function habitDayDatesByDate(habit: Habit | null, days: HabitDay[]): Map<string, 'done' | 'missed'> {
  const map = new Map<string, 'done' | 'missed'>();
  if (!habit) return map;
  const start = new Date(habit.created_at);
  start.setHours(0, 0, 0, 0);
  for (const day of days) {
    if (day.status !== 'done' && day.status !== 'missed') continue;
    const d = new Date(start);
    d.setDate(d.getDate() + (day.day_number - 1));
    map.set(dateKey(d), day.status);
  }
  return map;
}

/**
 * Same derivation as habitDayDatesByDate above, but unfiltered (every day,
 * including today's still-pending one) and mapping to the day_number
 * itself rather than a status — this is what makes a calendar cell
 * tappable at all (see the calendar-tap manual-correction feature). Since
 * day 1 is always the habit's creation date, there is never an entry for
 * any real date before that — a date "before the challenge was created"
 * simply isn't in this map, so it's never tappable; no separate guard is
 * needed for that case beyond this map lookup plus the backend's own
 * day_number >= 1 validation (see ActionService.editDayStatus).
 */
function dayNumbersByDate(habit: Habit | null, days: HabitDay[]): Map<string, number> {
  const map = new Map<string, number>();
  if (!habit) return map;
  const start = new Date(habit.created_at);
  start.setHours(0, 0, 0, 0);
  for (const day of days) {
    const d = new Date(start);
    d.setDate(d.getDate() + (day.day_number - 1));
    map.set(dateKey(d), day.day_number);
  }
  return map;
}

export default function HabitScreen({
  habitId, openCoachSignal, onHabitChanged, onOpenChallenges, onSignalConsumed,
}: Props) {
  const [habit, setHabit] = useState<Habit | null>(null);
  const [days, setDays] = useState<HabitDay[]>([]);
  const [current, setCurrent] = useState<CurrentIntervention | null>(null);
  const [response, setResponse] = useState<{ text: string; kind: string } | null>(null);
  // Only "missed" asks a reason now — snooze uses an explicit duration
  // picker instead (see snoozePromptOpen).
  const [pendingAction, setPendingAction] = useState<'missed' | null>(null);
  const [selectedReason, setSelectedReason] = useState<FeedbackReason | null>(null);
  const [snoozePromptOpen, setSnoozePromptOpen] = useState(false);
  const [showTimeEditor, setShowTimeEditor] = useState(false);
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
  const [advisorOpen, setAdvisorOpen] = useState(false);
  // Manual calendar-tap correction (see DayStatusEditModal.tsx) — null
  // means the sheet is closed. `date` is the real calendar Date the tapped
  // cell resolved to, shown in the sheet so the user can confirm exactly
  // which day they're about to change.
  const [dayEditorTarget, setDayEditorTarget] = useState<{ dayNumber: number; date: Date } | null>(null);
  const [editingDayCurrentStatus, setEditingDayCurrentStatus] = useState<HabitDay['status'] | null>(null);
  // Set only when a load() attempt fails AND there is nothing at all (not
  // even cached) to show — see load()'s javadoc. Spring Boot being
  // unreachable must never hang this screen on its loading spinner forever
  // (CLAUDE_CONTEXT.md's networking fix).
  const [loadError, setLoadError] = useState<string | null>(null);
  const insets = useSafeAreaInsets();
  const pageStyle = [styles.page, { paddingTop: insets.top }];

  useEffect(() => {
    api.getProfile().then((p) => setProfileName(p?.name ?? null)).catch(() => setProfileName(null));
  }, []);

  // Full-month grid. Which real calendar dates get a done/missed marker is
  // computed separately, in dayStatusByDate below (see habitDayDatesByDate's
  // javadoc) — this memo only builds the grid shape itself. Leading/trailing
  // days from adjacent months are included (dimmed) to fill out full weeks,
  // same as any standard calendar grid.
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
        dateKey: dateKey(d),
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

  // Recomputes automatically on every `days` change — i.e. right after
  // DONE/MISSED (and the auto-MISSED on a 3rd snooze), since those all flow
  // through load()/act() setting `days` from the fresh backend response.
  const dayStatusByDate = useMemo(() => habitDayDatesByDate(habit, days), [habit, days]);
  const dayNumberMap = useMemo(() => dayNumbersByDate(habit, days), [habit, days]);

  /**
   * Never throws — api.getHabitDetail/getCurrent already fall back to a
   * cached snapshot when Spring Boot is unreachable (see api.ts), so this
   * only fails when there is truly nothing (not even cached) for this
   * habit yet, in which case loadError is set and whatever was already on
   * screen is left alone rather than being cleared out from under the
   * user. Every existing caller (the mount effect, act(), snooze,
   * dismissResponse, saveTime) keeps working unchanged since none of them
   * need to special-case a thrown error anymore.
   */
  const load = useCallback(async (announce = false) => {
    try {
      // Fold in whatever Snooze/Stop happened via the notification action
      // while the app wasn't running (see AlarmActionReceiver.kt) before
      // reading habit state, so a 3rd-snooze-triggered auto-miss (or any
      // queued snooze) is already reflected in what loads below.
      await drainNativeEvents();
      const detail = await api.getHabitDetail(habitId);
      const cur = await api.getCurrent(habitId);
      setHabit(detail.habit);
      setDays(detail.days);
      setCurrent(cur);
      setLoadError(null);
      onHabitChanged(detail.habit);
      if (announce && !cur.finished && cur.intervention_text) {
        // Just shows the ringing UI (STOP ALARM/SNOOZE banner) — the audio
        // itself (beep alternating with spoken motivation) is entirely
        // native by this point (see AlarmRingService.kt), already playing
        // regardless of whether this effect ever runs.
        setRinging(true);
      }
      return cur;
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Could not load this challenge.');
      return null;
    }
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
        setResponse({ text: res.response_text, kind: res.response_kind || action });
        speakCoachMessage(res.response_text);
      } else {
        await load(false);
      }
    } catch {
      reportActionOffline();
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

      const snoozeResult = await api.act(habitId, 'snoozed');
      // Offline, api.act already computed the resulting count locally
      // (see api.ts) — reuse it directly instead of a second
      // network-then-fallback round trip (getHabitDetail) that would just
      // re-read the same value back, doubling the offline wait for
      // nothing. Online, the backend doesn't send this field, so this
      // falls through to the original getHabitDetail lookup unchanged.
      let snoozeCount: number;
      if (snoozeResult.generated_by === 'offline' && snoozeResult.snooze_count != null) {
        snoozeCount = snoozeResult.snooze_count;
      } else {
        const detail = await api.getHabitDetail(habitId);
        const dayNumber = current?.day_number ?? 1;
        snoozeCount = detail.days.find((d) => d.day_number === dayNumber)?.snooze_count ?? 0;
      }

      if (snoozeCount >= MAX_SNOOZES_PER_DAY) {
        await cancelSnoozeAlarm(habitId);
        const res = await api.act(habitId, 'missed');
        if (habit) await scheduleDailyAlarm(habit);
        if (res.response_text) {
          setResponse({ text: res.response_text, kind: res.response_kind || 'missed' });
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
    } catch {
      reportActionOffline();
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
    } catch {
      reportActionOffline();
    } finally {
      setBusy(false);
    }
  }

  /**
   * Manual calendar-tap correction — sets ONE specific day's status
   * directly (see api.editDayStatus). Deliberately no offline handling
   * beyond the existing fail-fast reportActionOffline() path: unlike
   * act()/saveTime(), this has no local-first branch, since the edit is
   * gated on server-computed state that must stay authoritative (see
   * api.ts's javadoc on this method). load(false) picks up every
   * consequence automatically — the calendar markers, "Day N of M"
   * shifting backward on a reset, or the habit flipping to finished if
   * this resolved the last pending day.
   */
  async function editDayStatus(status: EditableDayStatus) {
    if (!dayEditorTarget) return;
    setBusy(true);
    try {
      await api.editDayStatus(habitId, dayEditorTarget.dayNumber, status);
      setDayEditorTarget(null);
      setEditingDayCurrentStatus(null);
      await load(false);
    } catch {
      reportActionOffline();
    } finally {
      setBusy(false);
    }
  }

  function closeDayEditor() {
    setDayEditorTarget(null);
    setEditingDayCurrentStatus(null);
  }

  async function continueJourney() {
    try {
      const next = await api.continueHabit(habitId);
      onHabitChanged(next);
    } catch {
      reportActionOffline();
    }
  }
  async function stopJourney() {
    // Native alarms are cancelled locally regardless of backend
    // reachability — only the server-side stop (soft-stop status) needs
    // connectivity, so that part alone is what can fail here.
    await cancelDailyAlarm(habitId);
    await cancelSnoozeAlarm(habitId);
    try {
      await api.stopHabit(habitId);
      await load(false);
    } catch {
      reportActionOffline();
    }
  }

  if (!habit || !current) {
    // loadError set + nothing cached at all — a real dead end, not just a
    // slow network, so show that instead of spinning forever (see load()).
    if (loadError) {
      return (
        <View style={pageStyle}>
          <View style={styles.center}>
            <Text style={styles.offlineTitle}>Couldn't load this challenge</Text>
            <Text style={styles.offlineHint}>Check that the backend is reachable, then try again.</Text>
            <PrimaryButton label="Try again" onPress={() => load(false)} style={styles.primaryButtonSpacing} />
            <GhostButton label="← Go back" onPress={onOpenChallenges} />
          </View>
        </View>
      );
    }
    return (
      <View style={pageStyle}>
        <View style={styles.center}>
          <ActivityIndicator color={colors.primary} />
        </View>
      </View>
    );
  }

  if (response) {
    const icon = response.kind.startsWith('completion') ? '🎉' : '💛';
    return (
      <View style={pageStyle}>
        <View style={styles.responseWrap}>
          <View style={styles.responseCard}>
            <Text style={styles.responseIcon}>{icon}</Text>
            <Text style={styles.responseText}>{response.text}</Text>
            <PrimaryButton label="Continue" onPress={dismissResponse} style={styles.responseButton} />
          </View>
        </View>
      </View>
    );
  }

  if (current.finished) {
    return (
      <View style={pageStyle}>
        <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
          <View style={styles.card}>
            <Text style={type.h1} numberOfLines={1}>{habit.emoji} {habit.name}</Text>
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
    <View style={pageStyle}>
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* Header: greeting only. The reference shows notification/profile
            icons, but neither has a real function (no notification inbox,
            no profile-editing screen) — removed rather than kept as
            decorative-only controls (see CLAUDE_CONTEXT.md's UI redesign
            notes). Android's own notification infrastructure for the alarm
            is untouched; this only removes an in-app bell button. */}
        <View style={styles.headerRow}>
          <View style={styles.headerTextCol}>
            <Text style={styles.greetingSmall}>{greetingWord()},</Text>
            <Text style={styles.greetingName} numberOfLines={1}>{profileName ?? 'there'}!</Text>
            <View style={styles.greetingUnderline} />
            <Text style={styles.greetingSubtitle}>Small steps, big results 💪</Text>
          </View>
          {/* Habit Advisor entry point — opens a full-screen chat scoped to
              THIS habit (see AdvisorScreen.tsx/advisorStore.ts). Online-only;
              the screen itself shows a clear unavailable state when it can't
              be reached, never a fake/offline reply. */}
          <TouchableOpacity
            style={styles.advisorButton}
            onPress={() => setAdvisorOpen(true)}
            hitSlop={8}
            accessibilityLabel="Open Habit Advisor"
          >
            <Text style={styles.advisorButtonIcon}>🤖</Text>
          </TouchableOpacity>
        </View>

        {/* Full-month calendar — "<"/">" step the displayed month client-side,
            "Today" resets it. This is now the primary day-by-day progress
            visualization (the tree pill and 21-dot row were removed): each
            in-month date is checked against dayStatusByDate (derived from
            real habit.created_at + day.status, see habitDayDatesByDate's
            javadoc) and shows a green check for done / red X for missed.
            Recomputes automatically whenever `days` changes, i.e. right
            after any action that changes a day's status. */}
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
              {week.map((d) => {
                const status = d.inMonth ? dayStatusByDate.get(d.dateKey) : undefined;
                // Editable iff: it's a real day of THIS journey (in the
                // unfiltered dayNumberMap — which, since day 1 is always
                // the habit's creation date, never has an entry for any
                // date before the challenge existed), the habit is still
                // active, and that day has already been reached (never a
                // future day) — mirrors ActionService.editDayStatus's own
                // validation exactly, so a tap can never trigger a 400.
                const dayNum = d.inMonth ? dayNumberMap.get(d.dateKey) : undefined;
                const editable = dayNum != null && habit.status === 'active' && dayNum <= current.day_number;
                const Cell = editable ? TouchableOpacity : View;
                return (
                  <Cell
                    key={d.key}
                    activeOpacity={0.7}
                    style={[
                      styles.dateCircle,
                      !d.inMonth && styles.dateCircleOutMonth,
                      d.isToday && styles.dateCircleToday,
                      status === 'done' && styles.dateCircleDone,
                      status === 'missed' && styles.dateCircleMissed,
                    ]}
                    {...(editable ? {
                      onPress: () => {
                        const dayRow = days.find((dd) => dd.day_number === dayNum);
                        setDayEditorTarget({ dayNumber: dayNum!, date: new Date(d.key) });
                        setEditingDayCurrentStatus(dayRow?.status ?? 'pending');
                      },
                    } : {})}
                  >
                    <Text
                      style={[
                        styles.dateCircleText,
                        !d.inMonth && styles.dateCircleTextOutMonth,
                        d.isToday && styles.dateCircleTextToday,
                        status === 'done' && styles.dateCircleTextDone,
                        status === 'missed' && styles.dateCircleTextMissed,
                      ]}
                    >
                      {status === 'done' ? '✓' : status === 'missed' ? '✕' : d.date}
                    </Text>
                  </Cell>
                );
              })}
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
              onPress={() => setShowTimeEditor(true)}
            >
              <Text style={styles.changeTimeButtonText}>✏️ Change time</Text>
            </TouchableOpacity>
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

          <TimePickerModal
            visible={showTimeEditor}
            initialTime={habit.time_of_day}
            confirmLabel="Confirm time"
            busy={busy}
            onCancel={() => setShowTimeEditor(false)}
            onConfirm={(timeOfDay24h) => saveTime(timeOfDay24h)}
          />

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
                  onPress={() => setShowTimeEditor(true)}
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

      <AdvisorScreen habit={habit} visible={advisorOpen} onClose={() => setAdvisorOpen(false)} />

      <DayStatusEditModal
        visible={!!dayEditorTarget}
        dayNumber={dayEditorTarget?.dayNumber ?? null}
        date={dayEditorTarget?.date ?? null}
        currentStatus={editingDayCurrentStatus}
        busy={busy}
        onCancel={closeDayEditor}
        onChoose={editDayStatus}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  // Top inset comes from useSafeAreaInsets() (see pageStyle above), applied
  // per-render-branch since this screen has several return points
  // (loading/response/finished/main) that all share `page` as their root.
  // StatusBar.currentHeight was tried here previously but is unreliable
  // under Android edge-to-edge (mobile/android/gradle.properties'
  // edgeToEdgeEnabled=true) — react-native-safe-area-context queries the
  // OS for the real inset instead of guessing from the status bar height.
  page: { flex: 1, backgroundColor: colors.habitPageBg },
  scrollContent: { padding: spacing.md, paddingBottom: spacing.xl },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: spacing.xl },
  offlineTitle: { ...type.h1, fontSize: 19, textAlign: 'center', marginBottom: spacing.xs },
  offlineHint: { ...type.small, textAlign: 'center', marginBottom: spacing.lg },

  headerRow: { marginBottom: spacing.md, flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  headerTextCol: { flex: 1, paddingRight: spacing.sm },
  greetingSmall: { ...type.h2, fontSize: 18 },
  greetingName: { ...type.display, fontSize: 28, marginTop: -4 },
  greetingUnderline: { width: 60, height: 3, backgroundColor: colors.textHeading, borderRadius: 2, marginTop: 4, marginBottom: spacing.xs },
  greetingSubtitle: { ...type.small, fontSize: 13.5 },
  advisorButton: {
    width: 52, height: 52, borderRadius: 26, backgroundColor: colors.surfaceCard,
    alignItems: 'center', justifyContent: 'center', ...shadow.card,
  },
  advisorButtonIcon: { fontSize: 24 },

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
  dateCircleDone: { backgroundColor: colors.positiveBg },
  dateCircleMissed: { backgroundColor: colors.negativeBg },
  dateCircleText: { ...type.small, fontWeight: '600', color: colors.textPrimary, fontSize: 12.5 },
  dateCircleTextOutMonth: { color: colors.textMuted },
  dateCircleTextToday: { fontWeight: '800' },
  dateCircleTextDone: { color: colors.positive, fontWeight: '800' },
  dateCircleTextMissed: { color: colors.negative, fontWeight: '800' },

  habitCard: { backgroundColor: colors.accentGold, borderRadius: radii.lg, padding: spacing.md, marginBottom: spacing.md, ...shadow.card },
  habitHeadRow: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.xs },
  habitEmoji: { fontSize: 26, marginRight: spacing.sm },
  habitName: { ...type.h1, fontSize: 20, flex: 1 },
  habitMetaRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm, marginBottom: spacing.md },
  habitMetaTextWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md, flexShrink: 1 },
  habitMetaItem: { ...type.small, color: colors.onAccentGold, fontWeight: '700', fontSize: 12.5 },
  changeTimeButton: { borderWidth: 1.5, borderColor: colors.textHeading, borderRadius: radii.pill, paddingVertical: 6, paddingHorizontal: 12, backgroundColor: colors.surfaceCard },
  changeTimeButtonText: { color: colors.textPrimary, fontSize: 12, fontWeight: '700' },

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

  summaryText: { ...type.body, fontSize: 16.5, lineHeight: 24, marginTop: spacing.lg, marginBottom: spacing.md },
  primaryButtonSpacing: { marginTop: spacing.md, marginBottom: spacing.xs },

  responseWrap: { flex: 1, justifyContent: 'center', padding: spacing.lg },
  responseCard: { backgroundColor: colors.surfaceCard, borderRadius: radii.lg, padding: spacing.xl, alignItems: 'center', ...shadow.card },
  responseIcon: { fontSize: 34, marginBottom: spacing.md },
  responseText: { color: colors.textPrimary, fontSize: 18, fontWeight: '600', textAlign: 'center', lineHeight: 26, marginBottom: spacing.md },
  responseButton: { alignSelf: 'stretch' },
});
