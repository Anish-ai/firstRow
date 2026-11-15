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
  version: 0,
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
      'GET /api/v1/holds/me': () => ({ status: 204 }),
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
      'GET /api/v1/holds/me': () => ({ status: 204 }),
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
      'GET /api/v1/holds/me': () => ({ status: 204 }),
    });
    const user = userEvent.setup();
    renderPage();

    for (let n = 1; n <= 7; n++) {
      await user.click(await screen.findByRole('button', { name: `Section A, Row A, Seat ${n}, ₹450, available` }));
    }

    expect(await screen.findByText('You can select up to 6 seats at a time.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 7, ₹450, available' })).toHaveAttribute('aria-pressed', 'false');
  });

  describe('when the user already holds seats for this event', () => {
    const heldSeats = [seat(1, 'A', 1, 'HELD'), seat(2, 'A', 2, 'HELD'), seat(3, 'A', 3)];
    const myHold: Hold = {
      holdId: 'h-9',
      eventId: 1,
      expiresAt: new Date(Date.now() + 3 * 60_000).toISOString(),
      seats: [heldSeats[0]!, heldSeats[1]!],
      totalPaise: 90000,
    };

    function mockWithHold(extra: Parameters<typeof mockFetch>[0] = {}) {
      return mockFetch({
        'GET /api/v1/events/1': () => ({ status: 200, body: event }),
        'GET /api/v1/events/1/seats': () => ({ status: 200, body: { eventId: 1, seats: heldSeats } }),
        'GET /api/v1/holds/me': () => ({ status: 200, body: myHold }),
        ...extra,
      });
    }

    it('shows the held seats as the selection, with the countdown, and offers checkout', async () => {
      logIn();
      mockWithHold();
      renderPage();

      expect(await screen.findByText(/You're holding 2 seats/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 1, ₹450, available' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Section A, Row A, Seat 2, ₹450, available' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('timer')).toHaveTextContent(/^[23]:\d\d$/);
      expect(screen.getByRole('link', { name: 'Continue to checkout' })).toHaveAttribute('href', '/checkout/h-9');
      // No second hold: the "Hold N seats" button is replaced by editing the existing hold.
      expect(screen.queryByRole('button', { name: /hold \d seats? for 5 minutes/i })).not.toBeInTheDocument();
    });

    it('changing the selection updates the same hold (PUT) instead of creating a new one', async () => {
      logIn();
      const updated: Hold = { ...myHold, seats: [heldSeats[1]!, { ...heldSeats[2]!, status: 'HELD' }] };
      const fetchMock = mockWithHold({ 'PUT /api/v1/holds/h-9/seats': () => ({ status: 200, body: updated }) });
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Section A, Row A, Seat 1, ₹450, available' }));
      await user.click(screen.getByRole('button', { name: 'Section A, Row A, Seat 3, ₹450, available' }));
      await user.click(screen.getByRole('button', { name: 'Update hold (2 seats)' }));

      await waitFor(() =>
        expect(screen.getByTestId('announcer-polite')).toHaveTextContent('Hold updated: 2 seats. The timer keeps running.'),
      );
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
      expect(JSON.parse(String(put[1]!.body))).toEqual({ seatIds: [2, 3] });
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
    });

    it('can release all held seats', async () => {
      logIn();
      const fetchMock = mockWithHold({ 'DELETE /api/v1/holds/h-9': () => ({ status: 204 }) });
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Release all seats' }));

      await waitFor(() => expect(screen.getByTestId('announcer-polite')).toHaveTextContent('Your seats have been released.'));
      expect(fetchMock.mock.calls.some(([input, init]) => init?.method === 'DELETE' && String(input).endsWith('/api/v1/holds/h-9'))).toBe(true);
    });
  });
});
