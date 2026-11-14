import { useSyncExternalStore } from 'react';
import type { AuthResponse, User } from '../api/types';

/**
 * Who is logged in, kept in localStorage so it survives page reloads.
 * A plain module (not React context) because the fetch wrapper also needs the token outside components.
 *
 * Trade-off worth knowing: any script on the page can read localStorage, so an XSS bug could steal the token.
 * An httpOnly cookie can't be read by scripts, but then the API needs CSRF protection. For this project's
 * cross-origin setup (Vercel frontend, Render API) a bearer token in localStorage is the simpler choice.
 */
const KEY = 'firstrow.auth';

export interface Session {
  token: string;
  expiresAt: string;
  user: User;
}

type Listener = () => void;
const listeners = new Set<Listener>();
let cached: Session | null | undefined;

function read(): Session | null {
  if (cached !== undefined) return cached;
  try {
    const raw = localStorage.getItem(KEY);
    const session = raw ? (JSON.parse(raw) as Session) : null;
    cached = session && new Date(session.expiresAt).getTime() > Date.now() ? session : null;
  } catch {
    cached = null;
  }
  return cached;
}

function write(session: Session | null) {
  cached = session;
  try {
    if (session) localStorage.setItem(KEY, JSON.stringify(session));
    else localStorage.removeItem(KEY);
  } catch {
    // Storage blocked (private mode): the session just won't survive a reload.
  }
  listeners.forEach((listener) => listener());
}

export const authStore = {
  getSession: read,
  getToken: () => read()?.token ?? null,
  setFromResponse: (response: AuthResponse) =>
    write({ token: response.accessToken, expiresAt: response.expiresAt, user: response.user }),
  clear: () => write(null),
  subscribe(listener: Listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
  /** For tests: forget the in-memory copy so the next read goes to localStorage. */
  resetCache: () => {
    cached = undefined;
  },
};

/** React hook: re-renders when the user logs in or out. */
export function useSession(): Session | null {
  return useSyncExternalStore(authStore.subscribe, authStore.getSession, () => null);
}
