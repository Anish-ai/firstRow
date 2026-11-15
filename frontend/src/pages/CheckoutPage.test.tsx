import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Hold } from '../api/types';
import { holdStorage } from '../lib/holdStorage';
import { logIn, mockFetch, renderRoute, seat } from '../test/utils';
import { CheckoutPage } from './CheckoutPage';

function holdExpiringIn(ms: number): Hold {
  return {
    holdId: 'h-1',
    eventId: 1,
    expiresAt: new Date(Date.now() + ms).toISOString(),
    seats: [seat(1, 'A', 1)],
    totalPaise: 45000,
  };
}

function renderCheckout() {
  return renderRoute(<CheckoutPage />, {
    path: '/checkout/:holdId',
    url: '/checkout/h-1',
    extraRoutes: [{ path: '/bookings', element: <h1>My bookings page</h1> }],
  });
}

describe('CheckoutPage', () => {
  it('retries a failed payment with the SAME Idempotency-Key', async () => {
    logIn();
    let attempts = 0;
    const fetchMock = mockFetch({
      'GET /api/v1/holds/h-1': () => ({ status: 200, body: holdExpiringIn(5 * 60_000) }),
      'POST /api/v1/bookings': () =>
        ++attempts === 1
          ? { status: 503, body: { status: 503, detail: 'Temporarily unavailable' } }
          : { status: 201, body: { id: 42 } },
    });
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByRole('button', { name: 'Pay ₹450 (demo payment)' }));
    expect(await screen.findByText(/Temporarily unavailable/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay ₹450 (demo payment)' }));

    expect(await screen.findByRole('heading', { name: 'My bookings page' })).toBeInTheDocument();
    const keys = fetchMock.mock.calls
      .filter(([, init]) => init?.method === 'POST')
      .map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys[1]).toBe(keys[0]);
  });

  it('shows the countdown and disables payment once the hold expires', async () => {
    logIn();
    // The hold was active when loaded but its time ran out while the page was open.
    mockFetch({ 'GET /api/v1/holds/h-1': () => ({ status: 200, body: holdExpiringIn(-1000) }) });
    renderCheckout();

    expect(await screen.findByRole('timer')).toHaveTextContent('0:00');
    expect(screen.getByText('Hold expired')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pay ₹450 (demo payment)' })).toBeDisabled();
    await waitFor(() =>
      expect(screen.getByTestId('announcer-assertive')).toHaveTextContent('Your hold has expired. The seats have been released.'),
    );
  });

  it('counts down from the hold expiry time and links back to change seats', async () => {
    logIn();
    mockFetch({ 'GET /api/v1/holds/h-1': () => ({ status: 200, body: holdExpiringIn(4 * 60_000 + 30_500) }) });
    renderCheckout();

    expect(await screen.findByRole('timer')).toHaveTextContent('4:31');
    expect(screen.getByRole('link', { name: '← Change seats' })).toHaveAttribute('href', '/events/1');
  });

  it('explains when the hold is no longer active', async () => {
    logIn();
    holdStorage.save(holdExpiringIn(60_000));
    mockFetch({
      'GET /api/v1/holds/h-1': () => ({
        status: 404,
        body: { status: 404, detail: 'This hold is no longer active. It may have expired, been released or already been paid for.' },
      }),
    });
    renderCheckout();

    expect(await screen.findByText(/This hold is no longer active/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Choose seats again' })).toHaveAttribute('href', '/events/1');
    expect(screen.queryByRole('button', { name: /demo payment/ })).not.toBeInTheDocument();
  });
});
