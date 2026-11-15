import type { Seat } from '../api/types';
import { formatPaise, seatName } from '../lib/format';

/** Pure seat-map logic (no React), shared by the SeatMap component and its tests. */
export interface PriceTier {
  name: string;
  pricePaise: number;
  index: number;
}

/** Distinct tiers, most expensive first; their index picks the colour. */
export function priceTiers(seats: Seat[]): PriceTier[] {
  const byName = new Map<string, number>();
  seats.forEach((seat) => byName.set(seat.priceTier, seat.pricePaise));
  return [...byName.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([name, pricePaise], index) => ({ name, pricePaise, index }));
}

export type SeatState = 'available' | 'selected' | 'held' | 'booked' | 'taken';

/**
 * `mine` = seats in the user's own hold. They're HELD in the seat map, but for this user they are
 * selectable: selected (kept) or not (will be given back when the hold is updated).
 */
export function seatState(seat: Seat, selected: Set<number>, taken: Set<number>, mine: Set<number> = new Set()): SeatState {
  if (taken.has(seat.id)) return 'taken';
  if (mine.has(seat.id)) return selected.has(seat.id) ? 'selected' : 'available';
  if (seat.status === 'BOOKED') return 'booked';
  if (seat.status === 'HELD') return 'held';
  return selected.has(seat.id) ? 'selected' : 'available';
}

const STATE_WORDS: Record<SeatState, string> = {
  available: 'available',
  selected: 'available',
  held: 'held by someone else',
  booked: 'booked',
  taken: 'just taken by someone else',
};

export function seatLabel(seat: Seat, state: SeatState): string {
  return `${seatName(seat)}, ${formatPaise(seat.pricePaise)}, ${STATE_WORDS[state]}`;
}
