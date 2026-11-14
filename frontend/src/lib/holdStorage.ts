import type { Hold } from '../api/types';

/**
 * Keeps the current hold (and its Idempotency-Key) in sessionStorage, so reloading the checkout page
 * doesn't lose it. sessionStorage is per browser tab and cleared when the tab closes.
 */
const holdKey = (holdId: string) => `firstrow.hold.${holdId}`;
const idempotencyKeyFor = (holdId: string) => `firstrow.idem.${holdId}`;

function safeGet(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // storage blocked: the page still works until it's reloaded
  }
}

export const holdStorage = {
  save(hold: Hold) {
    safeSet(holdKey(hold.holdId), JSON.stringify(hold));
  },
  get(holdId: string): Hold | null {
    const raw = safeGet(holdKey(holdId));
    return raw ? (JSON.parse(raw) as Hold) : null;
  },
  remove(holdId: string) {
    try {
      sessionStorage.removeItem(holdKey(holdId));
      sessionStorage.removeItem(idempotencyKeyFor(holdId));
    } catch {
      // ignore
    }
  },
  /**
   * One Idempotency-Key per checkout attempt: created the first time, then reused for every retry
   * (double click, network timeout, page reload) so the server can never create two bookings.
   */
  idempotencyKey(holdId: string): string {
    const existing = safeGet(idempotencyKeyFor(holdId));
    if (existing) return existing;
    const key = crypto.randomUUID();
    safeSet(idempotencyKeyFor(holdId), key);
    return key;
  },
};
