jest.mock('./apiConfig', () => ({
  getApiBase: jest.fn(async () => 'http://test.local/api'),
  runBackendDiscovery: jest.fn(async () => undefined),
}));
jest.mock('./deviceId', () => ({
  getOrCreateDeviceId: jest.fn(async () => 'test-device'),
}));

import { api } from './api';

/**
 * Covers requirement §7/§8's "no offline chatbot fallback": unlike every
 * other api.ts method, sendAdvisorMessage must propagate a network
 * failure as a rejected promise, never swallow it into a local/offline
 * response. Also pins down the request contract (habit-scoped URL,
 * message+history body) matching AdvisorController's expectations.
 */
describe('api.sendAdvisorMessage', () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
    jest.clearAllMocks();
  });

  it('returns the parsed advisor response on a successful round-trip', async () => {
    globalThis.fetch = jest.fn(async () => ({
      ok: true,
      json: async () => ({ available: false, unavailable_reason: 'not configured yet' }),
    })) as unknown as typeof fetch;

    const result = await api.sendAdvisorMessage(1, 'hello', []);

    expect(result.available).toBe(false);
    expect(result.unavailable_reason).toBe('not configured yet');
  });

  it('propagates a network failure instead of returning any local/offline response', async () => {
    globalThis.fetch = jest.fn(async () => {
      throw new Error('network down');
    }) as unknown as typeof fetch;

    await expect(api.sendAdvisorMessage(1, 'hello', [])).rejects.toThrow();
  });

  it('sends message + history to the correct habit-scoped advisor URL', async () => {
    const fetchMock = jest.fn(async () => ({
      ok: true,
      json: async () => ({ available: false, unavailable_reason: 'x' }),
    }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await api.sendAdvisorMessage(42, 'What now?', [{ role: 'user', text: 'prior' }]);

    expect(fetchMock).toHaveBeenCalledWith(
      'http://test.local/api/habits/42/advisor/message',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ message: 'What now?', history: [{ role: 'user', text: 'prior' }] }),
      })
    );
  });

  it('rejects on a non-ok HTTP response rather than returning a fabricated result', async () => {
    globalThis.fetch = jest.fn(async () => ({
      ok: false,
      status: 500,
      text: async () => 'boom',
    })) as unknown as typeof fetch;

    await expect(api.sendAdvisorMessage(1, 'hello', [])).rejects.toThrow(/500/);
  });

  /**
   * Regression test for the "Couldn't reach the Habit Advisor" bug seen on a
   * physical device: sendAdvisorMessage used to share every other endpoint's
   * 5s REQUEST_TIMEOUT_MS, which aborted the request client-side while a
   * real LLM round-trip (Bedrock Mantle / GPT-OSS 120B, a reasoning model)
   * was still genuinely in flight and would have succeeded. A fetch mock
   * that only resolves once its AbortSignal is checked lets fake timers
   * prove the advisor call now tolerates a response slower than the old 5s
   * default without being aborted.
   */
  it('does not abort before the old 5s default would have, since it now uses a longer timeout for a real LLM round trip', async () => {
    jest.useFakeTimers();
    try {
      let aborted = false;
      globalThis.fetch = jest.fn((_url: unknown, opts?: RequestInit) => {
        return new Promise((resolve, reject) => {
          opts?.signal?.addEventListener('abort', () => {
            aborted = true;
            reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
          });
          setTimeout(() => {
            if (!aborted) {
              resolve({ ok: true, json: async () => ({ available: true, reply_text: 'a real reply' }) } as Response);
            }
          }, 10000); // slower than the old global 5s default, well within the advisor-specific timeout
        });
      }) as unknown as typeof fetch;

      const resultPromise = api.sendAdvisorMessage(1, 'hello', []);
      await jest.advanceTimersByTimeAsync(10000);
      const result = await resultPromise;

      expect(aborted).toBe(false);
      expect(result).toEqual({ available: true, reply_text: 'a real reply' });
    } finally {
      jest.useRealTimers();
    }
  });
});
