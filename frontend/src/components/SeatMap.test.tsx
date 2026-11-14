import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Seat } from '../api/types';
import { seat } from '../test/utils';
import { SeatLegend, SeatMap } from './SeatMap';

const seats: Seat[] = [
  seat(1, 'A', 1),
  seat(2, 'A', 2, 'HELD'),
  seat(3, 'A', 3, 'BOOKED'),
  seat(4, 'B', 1),
  seat(5, 'B', 2),
  { ...seat(6, 'A', 1, 'AVAILABLE', 'B'), priceTier: 'RECLINER', pricePaise: 75000 },
];

function renderMap(props: Partial<Parameters<typeof SeatMap>[0]> = {}) {
  const onToggle = vi.fn();
  render(
    <SeatMap seats={seats} selectedIds={new Set()} takenIds={new Set()} onToggle={onToggle} stageLabel="Screen" {...props} />,
  );
  return { onToggle };
}

const seatButton = (name: string) => screen.getByRole('button', { name });

describe('SeatMap', () => {
  it('renders every seat as a button whose label says its place, price and status', () => {
    renderMap({ selectedIds: new Set([4]) });

    expect(seatButton('Section A, Row A, Seat 1, ₹450, available')).toHaveAttribute('aria-pressed', 'false');
    expect(seatButton('Section A, Row B, Seat 1, ₹450, available')).toHaveAttribute('aria-pressed', 'true');
    expect(seatButton('Section A, Row A, Seat 2, ₹450, held by someone else')).toHaveAttribute('aria-disabled', 'true');
    expect(seatButton('Section A, Row A, Seat 3, ₹450, booked')).toHaveAttribute('aria-disabled', 'true');
    expect(seatButton('Section B, Row A, Seat 1, ₹750, available')).toBeInTheDocument();
  });

  it('marks seats reported as just taken', () => {
    renderMap({ takenIds: new Set([1]) });

    expect(seatButton('Section A, Row A, Seat 1, ₹450, just taken by someone else')).toHaveAttribute('aria-disabled', 'true');
  });

  it('does not use colour alone: each status renders a different symbol', () => {
    renderMap({ selectedIds: new Set([4]) });

    const states = ['available', 'selected', 'held', 'booked'];
    const glyphs = states.map((state) => document.querySelector(`[data-state="${state}"]`)?.innerHTML);
    expect(new Set(glyphs).size).toBe(states.length);
  });

  it('shows a legend with statuses and price tiers', () => {
    render(<SeatLegend seats={seats} />);

    expect(screen.getByText('Held by someone')).toBeInTheDocument();
    expect(screen.getByText('Booked')).toBeInTheDocument();
    expect(screen.getByText('recliner')).toBeInTheDocument();
    expect(screen.getByText('₹750')).toBeInTheDocument();
  });

  it('is keyboard operable: one Tab stop per section, arrows move, Space and Enter select', async () => {
    const user = userEvent.setup();
    const { onToggle } = renderMap();

    await user.tab();
    expect(seatButton('Section A, Row A, Seat 1, ₹450, available')).toHaveFocus();

    await user.keyboard('{ArrowRight}');
    expect(seatButton('Section A, Row A, Seat 2, ₹450, held by someone else')).toHaveFocus();

    await user.keyboard('{ArrowDown}');
    expect(seatButton('Section A, Row B, Seat 2, ₹450, available')).toHaveFocus();

    await user.keyboard(' ');
    expect(onToggle).toHaveBeenCalledWith(expect.objectContaining({ id: 5 }));

    await user.keyboard('{Home}{Enter}');
    expect(onToggle).toHaveBeenLastCalledWith(expect.objectContaining({ id: 4 }));

    // Tab leaves section A entirely and lands on section B's seat (not on seat A-A2, A-A3...).
    await user.tab();
    expect(seatButton('Section B, Row A, Seat 1, ₹750, available')).toHaveFocus();
  });

  it('does not select held or booked seats, by keyboard or mouse', async () => {
    const user = userEvent.setup();
    const { onToggle } = renderMap();

    await user.click(seatButton('Section A, Row A, Seat 3, ₹450, booked'));
    seatButton('Section A, Row A, Seat 2, ₹450, held by someone else').focus();
    await user.keyboard('{Enter}');

    expect(onToggle).not.toHaveBeenCalled();
  });
});
