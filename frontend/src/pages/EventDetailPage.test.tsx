import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { EventDetail, Hold } from '../api/types';
import { logIn, mockFetch, renderRoute, seat } from '../test/utils';
import { EventDetailPage } from './EventDetailPage';

const event: EventDetail = {
  id: 1,
  title: 'Starfall — Premiere Night',
  type: 'PREMIERE',
  startsAt: '2030-01-01T18:00:00Z',
  status: 'SCHEDULED',
  venue: { id: 1, name: 'Aurora Grand Cinema', city: 'Mumbai' },
  totalSeats: 3,
  availableSeats: 3,
};
const seats = [seat(1, 'A', 1), seat(2, 'A', 2), seat(3, 'A', 3)];

function renderPage() {
  return renderRoute(<EventDetailPage />, {
    path: '/events/:eventId',
    url: '/events/1',
    extraRoutes: [{ path: '/checkout/:holdId', element: <h1>Checkout page</h1> }],
  });
}

describe('EventDetailPage', () => {
  it('on 409 highlights the taken seat and shows a friendly message', async () => {
    logIn();
    mockFetch({
      'GET /api/v1/events/1': () => ({ status: 200, body: event }),
      'GET /api/v1/events/1/seats': () => ({ status: 200, body: { eventId: 1, seats } }),
      'POST /api/v1/events/1/holds': () => ({
        status: 409,
        body: { status: 409, title: 'Conflict', detail: 'Sorry, some of these seats were just taken.', unavailableSeatIds: [2] },
      }),
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Section A, Row A, Seat 1, ₹450, available' }));
    await user.click(screen.getByRole('button', { name: 'Section A, Row A, Seat 2, ₹450, available' }));
    await user.click(screen.getByRole('button', { name: /hold 2 seats/i }));

    const message = 'Sorry, Section A, Row A, Seat 2 was just taken by someone else. Please pick another seat.';
    // Visible alert...
    expect(await screen.findByText(message)).toHaveAttribute('role', 'alert');
    // ...the seat is visibly and audibly marked...
    expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 2, ₹450, just taken by someone else' })).toBeInTheDocument();
    // ...it's dropped from the selection, the other seat stays selected...
    expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 1, ₹450, available' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /hold 1 seat for 5 minutes/i })).toBeEnabled();
    // ...and screen readers are told immediately.
    await waitFor(() => expect(screen.getByTestId('announcer-assertive')).toHaveTextContent(message));
  });

  it('on success saves the hold and goes to checkout', async () => {
    logIn();
    const hold: Hold = { holdId: 'h-1', eventId: 1, expiresAt: '2030-01-01T00:05:00Z', seats: [seats[0]!], totalPaise: 45000 };
    const fetchMock = mockFetch({
      'GET /api/v1/events/1': () => ({ status: 200, body: event }),
      'GET /api/v1/events/1/seats': () => ({ status: 200, body: { eventId: 1, seats } }),
      'POST /api/v1/events/1/holds': () => ({ status: 201, body: hold }),
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Section A, Row A, Seat 1, ₹450, available' }));
    await user.click(screen.getByRole('button', { name: /hold 1 seat/i }));

    expect(await screen.findByRole('heading', { name: 'Checkout page' })).toBeInTheDocument();
    expect(JSON.parse(sessionStorage.getItem('firstrow.hold.h-1')!)).toEqual(hold);
    const holdCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(JSON.parse(String(holdCall[1]!.body))).toEqual({ seatIds: [1] });
    expect(new Headers(holdCall[1]!.headers).get('Authorization')).toBe('Bearer test-token');
  });

  it('asks logged-out visitors to log in instead of holding', async () => {
    mockFetch({
      'GET /api/v1/events/1': () => ({ status: 200, body: event }),
      'GET /api/v1/events/1/seats': () => ({ status: 200, body: { eventId: 1, seats } }),
    });
    renderPage();

    expect(await screen.findByRole('link', { name: 'Log in to hold seats' })).toBeInTheDocument();
  });

  it('limits a selection to 6 seats', async () => {
    logIn();
    const many = Array.from({ length: 7 }, (_, i) => seat(i + 1, 'A', i + 1));
    mockFetch({
      'GET /api/v1/events/1': () => ({ status: 200, body: event }),
      'GET /api/v1/events/1/seats': () => ({ status: 200, body: { eventId: 1, seats: many } }),
    });
    const user = userEvent.setup();
    renderPage();

    for (let n = 1; n <= 7; n++) {
      await user.click(await screen.findByRole('button', { name: `Section A, Row A, Seat ${n}, ₹450, available` }));
    }

    expect(await screen.findByText('You can select up to 6 seats at a time.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 7, ₹450, available' })).toHaveAttribute('aria-pressed', 'false');
  });
});
