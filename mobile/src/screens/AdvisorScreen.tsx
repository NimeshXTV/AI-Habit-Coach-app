import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, FlatList, KeyboardAvoidingView, Modal, Platform, StyleSheet, Text, TextInput,
  TouchableOpacity, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { api } from '../api';
import { appendAdvisorMessage, getAdvisorConversation, type AdvisorMessage } from '../advisorStore';
import { colors, radii, shadow, spacing, type } from '../theme';
import type { Habit } from '../types';

interface Props {
  habit: Habit;
  visible: boolean;
  onClose: () => void;
}

/** How many of THIS habit's past turns are sent alongside a new message —
 * mirrors (but doesn't need to exactly equal) the backend's own
 * AdvisorService.MAX_HISTORY_TURNS cap, which re-bounds it server-side
 * regardless of what's sent here. */
const HISTORY_TURNS_SENT = 20;

/**
 * Full-screen, slide-up Habit Advisor chat — one conversation per habit
 * (see advisorStore.ts, keyed by habit.id). ONLINE-ONLY by design: there is
 * no local/template/offline fallback anywhere in this file. Every reply
 * comes from POST /api/habits/{id}/advisor/message or the screen shows an
 * explicit unavailable notice — never a fabricated response (see
 * api.ts's sendAdvisorMessage and requirement §7 of the Habit Advisor
 * build-out).
 */
export default function AdvisorScreen({ habit, visible, onClose }: Props) {
  const insets = useSafeAreaInsets();
  const [messages, setMessages] = useState<AdvisorMessage[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  // A status notice (offline / advisor not configured yet) — deliberately
  // NOT persisted to advisorStore and NOT rendered as a chat bubble: it's a
  // transient status about THIS attempt, not a real advisor turn, so
  // reopening the conversation later never replays a stale "unavailable"
  // line as if it were part of the conversation.
  const [notice, setNotice] = useState<string | null>(null);
  const listRef = useRef<FlatList<AdvisorMessage>>(null);

  // Reloads THIS habit's own conversation every time the advisor is opened
  // — keyed strictly by habit.id, so switching habits (closing this modal
  // and opening a different habit's advisor) can never show a mix of two
  // habits' history. See advisorStore.ts's javadoc.
  useEffect(() => {
    if (!visible) return;
    setLoaded(false);
    setNotice(null);
    setInput('');
    getAdvisorConversation(habit.id).then((existing) => {
      setMessages(existing);
      setLoaded(true);
    });
  }, [visible, habit.id]);

  const scrollToEnd = useCallback(() => {
    requestAnimationFrame(() => listRef.current?.scrollToEnd({ animated: true }));
  }, []);

  async function send() {
    const text = input.trim();
    if (!text || sending) return;
    setInput('');
    setNotice(null);

    const afterUser = await appendAdvisorMessage(habit.id, { role: 'user', text, at: Date.now() });
    setMessages(afterUser);
    scrollToEnd();
    setSending(true);

    try {
      const history = afterUser
        .slice(0, -1)
        .slice(-HISTORY_TURNS_SENT)
        .map((m) => ({ role: m.role, text: m.text }));
      const result = await api.sendAdvisorMessage(habit.id, text, history);

      if (result.available && result.reply_text) {
        const afterReply = await appendAdvisorMessage(habit.id, { role: 'advisor', text: result.reply_text, at: Date.now() });
        setMessages(afterReply);
      } else {
        setNotice(result.unavailable_reason || "The Habit Advisor isn't available right now.");
      }
    } catch {
      // Thrown by api.ts only for a genuine network failure/timeout — the
      // backend being unreachable is NOT the same as `available: false`
      // above, but both are shown the same way: a clear, honest notice,
      // never a local/offline-generated reply (see CLAUDE_CONTEXT.md's
      // Habit Advisor requirement: online-only, no offlineCoach.ts here).
      setNotice("Couldn't reach the Habit Advisor — check your internet connection and try again.");
    } finally {
      setSending(false);
      scrollToEnd();
    }
  }

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View style={[styles.page, { paddingTop: insets.top }]}>
        <View style={styles.header}>
          <View style={styles.headerTextCol}>
            <Text style={styles.headerEyebrow}>Habit Advisor</Text>
            <Text style={styles.headerTitle} numberOfLines={1}>{habit.emoji} {habit.name}</Text>
          </View>
          <TouchableOpacity style={styles.closeButton} onPress={onClose} hitSlop={10} accessibilityLabel="Close Habit Advisor">
            <Text style={styles.closeButtonText}>✕</Text>
          </TouchableOpacity>
        </View>

        <KeyboardAvoidingView
          style={styles.flex}
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        >
          {!loaded ? (
            <View style={styles.center}>
              <ActivityIndicator color={colors.primary} />
            </View>
          ) : messages.length === 0 ? (
            <View style={styles.center}>
              <Text style={styles.emptyIcon}>🤖</Text>
              <Text style={styles.emptyTitle}>Ask about {habit.name.toLowerCase()}</Text>
              <Text style={styles.emptyHint}>
                Your Habit Advisor can answer questions about this challenge. It only works online.
              </Text>
            </View>
          ) : (
            <FlatList
              ref={listRef}
              data={messages}
              keyExtractor={(m) => m.id}
              contentContainerStyle={styles.messageList}
              renderItem={({ item }) => (
                <View style={[styles.bubbleRow, item.role === 'user' ? styles.bubbleRowUser : styles.bubbleRowAdvisor]}>
                  <View style={[styles.bubble, item.role === 'user' ? styles.bubbleUser : styles.bubbleAdvisor]}>
                    <Text style={item.role === 'user' ? styles.bubbleTextUser : styles.bubbleTextAdvisor}>
                      {item.text}
                    </Text>
                  </View>
                </View>
              )}
              onContentSizeChange={scrollToEnd}
            />
          )}

          {sending && (
            <View style={styles.typingRow}>
              <ActivityIndicator size="small" color={colors.textSecondary} />
              <Text style={styles.typingText}>Thinking…</Text>
            </View>
          )}

          {notice && (
            <View style={styles.noticeBox}>
              <Text style={styles.noticeIcon}>📡</Text>
              <Text style={styles.noticeText}>{notice}</Text>
            </View>
          )}

          <View style={[styles.inputRow, { paddingBottom: Math.max(insets.bottom, spacing.sm) }]}>
            <TextInput
              style={styles.input}
              value={input}
              onChangeText={setInput}
              placeholder="Ask your Habit Advisor…"
              placeholderTextColor={colors.textMuted}
              multiline
              editable={!sending}
            />
            <TouchableOpacity
              style={[styles.sendButton, (!input.trim() || sending) && styles.sendButtonDisabled]}
              onPress={send}
              disabled={!input.trim() || sending}
              accessibilityLabel="Send message"
            >
              <Text style={styles.sendButtonText}>➤</Text>
            </TouchableOpacity>
          </View>
        </KeyboardAvoidingView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.surface },
  flex: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: spacing.xl },

  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: spacing.lg, paddingBottom: spacing.md, paddingTop: spacing.sm,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  headerTextCol: { flex: 1, paddingRight: spacing.sm },
  headerEyebrow: { ...type.tiny, textTransform: 'uppercase', letterSpacing: 0.6 },
  headerTitle: { ...type.h1, fontSize: 20, marginTop: 2 },
  closeButton: {
    width: 36, height: 36, borderRadius: 18, backgroundColor: colors.surfaceAlt,
    alignItems: 'center', justifyContent: 'center',
  },
  closeButtonText: { color: colors.textPrimary, fontSize: 16, fontWeight: '700' },

  emptyIcon: { fontSize: 40, marginBottom: spacing.md },
  emptyTitle: { ...type.h2, textAlign: 'center', marginBottom: spacing.xs },
  emptyHint: { ...type.small, textAlign: 'center', lineHeight: 20 },

  messageList: { padding: spacing.md, paddingBottom: spacing.sm },
  bubbleRow: { flexDirection: 'row', marginBottom: spacing.sm },
  bubbleRowUser: { justifyContent: 'flex-end' },
  bubbleRowAdvisor: { justifyContent: 'flex-start' },
  bubble: { maxWidth: '82%', borderRadius: radii.lg, paddingVertical: 10, paddingHorizontal: 14, ...shadow.soft },
  bubbleUser: { backgroundColor: colors.primary, borderBottomRightRadius: 4 },
  bubbleAdvisor: { backgroundColor: colors.surfaceCard, borderBottomLeftRadius: 4 },
  bubbleTextUser: { color: colors.onPrimary, fontSize: 15, lineHeight: 21, fontWeight: '500' },
  bubbleTextAdvisor: { color: colors.textPrimary, fontSize: 15, lineHeight: 21, fontWeight: '500' },

  typingRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingHorizontal: spacing.lg, paddingBottom: spacing.xs },
  typingText: { ...type.tiny },

  noticeBox: {
    flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm,
    marginHorizontal: spacing.md, marginBottom: spacing.sm, padding: spacing.md,
    backgroundColor: colors.surfaceAlt, borderRadius: radii.md, borderWidth: 1, borderColor: colors.border,
  },
  noticeIcon: { fontSize: 16 },
  noticeText: { ...type.small, flex: 1, lineHeight: 19 },

  inputRow: {
    flexDirection: 'row', alignItems: 'flex-end', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingTop: spacing.sm,
    borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.surface,
  },
  input: {
    flex: 1, backgroundColor: colors.surfaceCard, borderRadius: radii.lg, borderWidth: 1.5, borderColor: colors.border,
    paddingHorizontal: 14, paddingVertical: 10, maxHeight: 110, fontSize: 15, color: colors.textPrimary,
  },
  sendButton: {
    width: 44, height: 44, borderRadius: 22, backgroundColor: colors.primary,
    alignItems: 'center', justifyContent: 'center', ...shadow.button,
  },
  sendButtonDisabled: { opacity: 0.45 },
  sendButtonText: { color: colors.onPrimary, fontSize: 18, fontWeight: '700' },
});
