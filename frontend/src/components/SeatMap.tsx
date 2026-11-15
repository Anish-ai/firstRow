import { useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { Seat } from '../api/types';
import { formatPaise } from '../lib/format';
import { priceTiers, seatLabel, seatState, type SeatState } from './seatModel';

/** Border colours for price tiers (most expensive first). Listed in full so Tailwind keeps the classes. */
// Earthy tones that sit with the grey/brown theme and stay distinct from the orange "selected" fill.
const TIER_BORDERS = ['border-[#6a4e3a]', 'border-[#b8966f]', 'border-[#5f7180]', 'border-[#8a8452]', 'border-[#a3655a]'];
const TIER_SWATCHES = ['bg-[#6a4e3a]', 'bg-[#b8966f]', 'bg-[#5f7180]', 'bg-[#8a8452]', 'bg-[#a3655a]'];

interface SeatMapProps {
  seats: Seat[];
  selectedIds: Set<number>;
  /** Seats that a 409 told us were taken; highlighted until the user picks again. */
  takenIds: Set<number>;
  /** Seats in the user's own hold: shown as selectable even though they're HELD. */
  mineIds?: Set<number>;
  onToggle: (seat: Seat) => void;
  stageLabel: string;
}

const NONE = new Set<number>();

export function SeatMap({ seats, selectedIds, takenIds, mineIds = NONE, onToggle, stageLabel }: SeatMapProps) {
  const tiers = useMemo(() => priceTiers(seats), [seats]);
  const tierIndex = useMemo(() => new Map(tiers.map((t) => [t.name, t.index])), [tiers]);
  const sections = useMemo(() => groupSeats(seats), [seats]);

  return (
    <div>
      <p id="seatmap-help" className="mb-6 text-sm text-muted">
        Use <kbd className={KBD}>Tab</kbd> to move between sections, arrow keys to move between seats, and{' '}
        <kbd className={KBD}>Enter</kbd> or <kbd className={KBD}>Space</kbd> to select a seat.
      </p>
      <Stage label={stageLabel} />
      <div className="space-y-10">
        {sections.map((section) => (
          <SeatSection
            key={section.name}
            name={section.name}
            rows={section.rows}
            selectedIds={selectedIds}
            takenIds={takenIds}
            mineIds={mineIds}
            tierIndex={tierIndex}
            onToggle={onToggle}
          />
        ))}
      </div>
    </div>
  );
}

interface SectionProps {
  name: string;
  rows: { label: string; seats: Seat[] }[];
  selectedIds: Set<number>;
  takenIds: Set<number>;
  mineIds: Set<number>;
  tierIndex: Map<string, number>;
  onToggle: (seat: Seat) => void;
}

/**
 * One section = one Tab stop ("roving tabindex"): only the active seat has tabIndex=0,
 * arrow keys move the active seat. Without this, a 400-seat stadium would need 400 Tab presses.
 */
function SeatSection({ name, rows, selectedIds, takenIds, mineIds, tierIndex, onToggle }: SectionProps) {
  const [activeId, setActiveId] = useState<number | null>(null);
  const buttons = useRef(new Map<number, HTMLButtonElement>());
  const firstSeatId = rows[0]?.seats[0]?.id ?? null;
  const tabStopId = activeId ?? firstSeatId;
  const headingId = `section-${name}-heading`;

  function moveTo(seat: Seat | undefined) {
    if (!seat) return;
    setActiveId(seat.id);
    buttons.current.get(seat.id)?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, rowIndex: number, seatIndex: number) {
    const row = rows[rowIndex]!;
    const nearestIn = (r: number) => {
      const target = rows[r];
      return target?.seats[Math.min(seatIndex, target.seats.length - 1)];
    };
    const moves: Record<string, () => Seat | undefined> = {
      ArrowRight: () => row.seats[seatIndex + 1],
      ArrowLeft: () => row.seats[seatIndex - 1],
      ArrowDown: () => nearestIn(rowIndex + 1),
      ArrowUp: () => nearestIn(rowIndex - 1),
      Home: () => row.seats[0],
      End: () => row.seats[row.seats.length - 1],
    };
    const move = moves[event.key];
    if (move) {
      event.preventDefault();
      moveTo(move());
    }
  }

  return (
    <section aria-labelledby={headingId}>
      <h3 id={headingId} className="mb-3 flex items-center gap-3 text-xs font-semibold uppercase tracking-[0.2em] text-cocoa">
        Section {name}
        <span aria-hidden="true" className="h-px flex-1 bg-line" />
      </h3>
      <div className="overflow-x-auto pb-2">
        <div className="mx-auto flex w-fit flex-col gap-1.5">
          {rows.map((row, rowIndex) => (
            <div key={row.label} role="group" aria-label={`Section ${name}, Row ${row.label}`} className="flex items-center gap-1">
              <span className="w-5 shrink-0 text-center text-xs font-semibold text-muted" aria-hidden="true">
                {row.label}
              </span>
              {row.seats.map((seat, seatIndex) => (
                <SeatButton
                  key={seat.id}
                  seat={seat}
                  state={seatState(seat, selectedIds, takenIds, mineIds)}
                  tierIndex={tierIndex.get(seat.priceTier) ?? 0}
                  tabIndex={seat.id === tabStopId ? 0 : -1}
                  buttonRef={(el) => {
                    if (el) buttons.current.set(seat.id, el);
                    else buttons.current.delete(seat.id);
                  }}
                  onFocus={() => setActiveId(seat.id)}
                  onKeyDown={(event) => onKeyDown(event, rowIndex, seatIndex)}
                  onToggle={onToggle}
                />
              ))}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

interface SeatButtonProps {
  seat: Seat;
  state: SeatState;
  tierIndex: number;
  tabIndex: number;
  buttonRef: (el: HTMLButtonElement | null) => void;
  onFocus: () => void;
  onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => void;
  onToggle: (seat: Seat) => void;
}

const STATE_CLASSES: Record<SeatState, string> = {
  available: 'bg-white text-ink hover:bg-accent-soft hover:-translate-y-px',
  selected: 'bg-accent text-white shadow-[0_3px_8px_-2px_rgb(191_74_18/0.6)]',
  held: 'bg-sand text-cocoa pattern-held cursor-not-allowed',
  booked: 'bg-[#8f877e] text-white cursor-not-allowed',
  taken: 'bg-red-100 text-red-800 ring-4 ring-red-600 cursor-not-allowed',
};

function SeatButton({ seat, state, tierIndex, tabIndex, buttonRef, onFocus, onKeyDown, onToggle }: SeatButtonProps) {
  const selectable = state === 'available' || state === 'selected';
  const label = seatLabel(seat, state);
  return (
    <button
      ref={buttonRef}
      type="button"
      tabIndex={tabIndex}
      aria-label={label}
      title={label}
      // aria-pressed tells assistive tech whether the seat is selected (a toggle button).
      aria-pressed={selectable ? state === 'selected' : undefined}
      // aria-disabled (not `disabled`) keeps unavailable seats focusable, so keyboard and
      // screen-reader users can still discover them and hear why they can't be chosen.
      aria-disabled={selectable ? undefined : true}
      data-state={state}
      onFocus={onFocus}
      onKeyDown={onKeyDown}
      onClick={() => selectable && onToggle(seat)}
      className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-t-lg rounded-b-sm border-2 text-[11px] font-semibold transition ${TIER_BORDERS[tierIndex % TIER_BORDERS.length]} ${STATE_CLASSES[state]}`}
    >
      <SeatGlyph state={state} number={seat.number} />
    </button>
  );
}

/** Each state has its own shape, so colour is never the only way to tell them apart. */
export function SeatGlyph({ state, number }: { state: SeatState; number?: number }) {
  switch (state) {
    case 'selected':
      return (
        <svg aria-hidden="true" viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.5">
          <path d="M3 8.5l3 3 7-7" />
        </svg>
      );
    case 'booked':
      return (
        <svg aria-hidden="true" viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2.5">
          <path d="M4 4l8 8M12 4l-8 8" />
        </svg>
      );
    case 'held':
      return (
        <svg aria-hidden="true" viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8">
          <circle cx="8" cy="8" r="6" />
          <path d="M8 4.5V8l2.5 1.5" />
        </svg>
      );
    case 'taken':
      return <span aria-hidden="true">!</span>;
    default:
      return <span aria-hidden="true">{number}</span>;
  }
}

export function SeatLegend({ seats }: { seats: Seat[] }) {
  const tiers = priceTiers(seats);
  const states: { state: SeatState; text: string }[] = [
    { state: 'available', text: 'Available' },
    { state: 'selected', text: 'Your selection' },
    { state: 'held', text: 'Held by someone' },
    { state: 'booked', text: 'Booked' },
  ];
  return (
    <div className="flex flex-wrap gap-x-10 gap-y-4 text-sm">
      <div>
        <h3 className="eyebrow mb-2">Seat status</h3>
        <ul className="flex flex-wrap gap-x-4 gap-y-1.5">
          {states.map(({ state, text }) => (
            <li key={state} className="flex items-center gap-1.5">
              <span
                className={`flex h-6 w-6 items-center justify-center rounded-t-md border-2 border-tan text-[10px] ${STATE_CLASSES[state]}`}
              >
                <SeatGlyph state={state} number={1} />
              </span>
              {text}
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h3 className="eyebrow mb-2">Price (seat outline)</h3>
        <ul className="flex flex-wrap gap-x-4 gap-y-1.5">
          {tiers.map((tier) => (
            <li key={tier.name} className="flex items-center gap-1.5">
              <span aria-hidden="true" className={`h-3 w-3 rounded-full ${TIER_SWATCHES[tier.index % TIER_SWATCHES.length]}`} />
              <span className="capitalize">{tier.name.toLowerCase()}</span>
              <span className="text-muted">{formatPaise(tier.pricePaise)}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

const KBD = 'rounded-md border border-line border-b-2 bg-surface px-1.5 py-0.5 font-sans text-xs text-ink';

/** The screen (or pitch) as a curved, glowing band, so it's obvious which way the seats face. */
function Stage({ label }: { label: string }) {
  return (
    <div aria-hidden="true" className="mx-auto mb-10 max-w-lg text-center">
      <svg viewBox="0 0 400 40" className="w-full" preserveAspectRatio="none">
        <defs>
          <linearGradient id="stage-glow" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--color-tan)" stopOpacity="0.45" />
            <stop offset="1" stopColor="var(--color-tan)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d="M10 8 Q200 -6 390 8 L370 40 Q200 26 30 40 Z" fill="url(#stage-glow)" />
        <path d="M10 8 Q200 -6 390 8" fill="none" stroke="var(--color-cocoa)" strokeWidth="4" strokeLinecap="round" />
      </svg>
      <span className="text-[11px] font-semibold uppercase tracking-[0.35em] text-cocoa">{label}</span>
    </div>
  );
}

function groupSeats(seats: Seat[]) {
  const sections = new Map<string, Map<string, Seat[]>>();
  for (const seat of seats) {
    const rows = sections.get(seat.section) ?? new Map<string, Seat[]>();
    const row = rows.get(seat.row) ?? [];
    row.push(seat);
    rows.set(seat.row, row);
    sections.set(seat.section, rows);
  }
  return [...sections.entries()].map(([name, rows]) => ({
    name,
    rows: [...rows.entries()].map(([label, rowSeats]) => ({
      label,
      seats: [...rowSeats].sort((a, b) => a.number - b.number),
    })),
  }));
}
