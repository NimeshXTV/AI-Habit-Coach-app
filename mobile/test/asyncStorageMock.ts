/**
 * In-memory stand-in for @react-native-async-storage/async-storage, used
 * only under Jest (see jest.config.js's moduleNameMapper) — real app code
 * never imports this file. Exposes `clear()` for tests' beforeEach.
 */
const store = new Map<string, string>();

const AsyncStorageMock = {
  getItem: async (key: string): Promise<string | null> => (store.has(key) ? store.get(key)! : null),
  setItem: async (key: string, value: string): Promise<void> => {
    store.set(key, value);
  },
  removeItem: async (key: string): Promise<void> => {
    store.delete(key);
  },
  clear: async (): Promise<void> => {
    store.clear();
  },
};

export default AsyncStorageMock;
