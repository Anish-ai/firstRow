import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { authStore } from '../auth/authStore';

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
  authStore.resetCache();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
