/**
 * Minimal Jest setup for plain-TypeScript unit tests only (advisorStore.ts,
 * api.ts's advisor contract) — deliberately NOT jest-expo/React Native
 * Testing Library, since nothing here renders a component; see
 * AdvisorScreen.tsx's own javadoc-style note for why UI rendering tests
 * were out of scope for this change. Adding the full RN test renderer
 * stack would be a much bigger, unrelated infrastructure change than this
 * task called for.
 */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['<rootDir>/src/**/*.test.ts'],
  moduleNameMapper: {
    '^@react-native-async-storage/async-storage$': '<rootDir>/test/asyncStorageMock.ts',
    '^react-native$': '<rootDir>/test/reactNativeMock.ts',
  },
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: '<rootDir>/tsconfig.jest.json' }],
  },
};
