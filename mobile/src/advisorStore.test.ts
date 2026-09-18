import AsyncStorage from '@react-native-async-storage/async-storage';
import { appendAdvisorMessage, clearAdvisorConversation, getAdvisorConversation, MAX_STORED_MESSAGES } from './advisorStore';

/** Covers requirement §3 (per-habit conversation isolation, local
 * persistence, bounded history) directly against advisorStore.ts. */
describe('advisorStore', () => {
  beforeEach(async () => {
    await (AsyncStorage as unknown as { clear: () => Promise<void> }).clear();
  });

  it('starts empty for a habit with no conversation yet', async () => {
    expect(await getAdvisorConversation(1)).toEqual([]);
  });

  it('never lets one habit\'s conversation appear under another habit\'s id', async () => {
    await appendAdvisorMessage(1, { role: 'user', text: 'Gym question', at: 1 });
    await appendAdvisorMessage(2, { role: 'user', text: 'Study question', at: 2 });

    const gym = await getAdvisorConversation(1);
    const study = await getAdvisorConversation(2);

    expect(gym).toHaveLength(1);
    expect(gym[0].text).toBe('Gym question');
    expect(study).toHaveLength(1);
    expect(study[0].text).toBe('Study question');
  });

  it('persists appended messages so reopening the same habit sees them again', async () => {
    await appendAdvisorMessage(5, { role: 'user', text: 'hi', at: 1 });
    await appendAdvisorMessage(5, { role: 'advisor', text: 'hello', at: 2 });

    const reopened = await getAdvisorConversation(5);
    expect(reopened.map((m) => m.text)).toEqual(['hi', 'hello']);
  });

  it('bounds stored history to MAX_STORED_MESSAGES, dropping the oldest turns first', async () => {
    for (let i = 0; i < MAX_STORED_MESSAGES + 10; i++) {
      await appendAdvisorMessage(7, { role: i % 2 === 0 ? 'user' : 'advisor', text: `msg ${i}`, at: i });
    }

    const messages = await getAdvisorConversation(7);
    expect(messages).toHaveLength(MAX_STORED_MESSAGES);
    expect(messages[0].text).toBe('msg 10');
    expect(messages[messages.length - 1].text).toBe(`msg ${MAX_STORED_MESSAGES + 9}`);
  });

  it('clearAdvisorConversation only removes that one habit\'s history', async () => {
    await appendAdvisorMessage(1, { role: 'user', text: 'a', at: 1 });
    await appendAdvisorMessage(2, { role: 'user', text: 'b', at: 1 });

    await clearAdvisorConversation(1);

    expect(await getAdvisorConversation(1)).toEqual([]);
    expect(await getAdvisorConversation(2)).toHaveLength(1);
  });

  it('assigns every message a unique id', async () => {
    await appendAdvisorMessage(9, { role: 'user', text: 'x', at: 1 });
    await appendAdvisorMessage(9, { role: 'user', text: 'y', at: 2 });

    const all = await getAdvisorConversation(9);
    expect(new Set(all.map((m) => m.id)).size).toBe(all.length);
  });
});
