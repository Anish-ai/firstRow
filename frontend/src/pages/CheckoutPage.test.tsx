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
    holdStorage.save(holdExpiringIn(5 * 60_000));
    let attempts = 0;
    const fetchMock = mockFetch({
      'POST /api/v1/bookings': () =>
        ++attempts === 1
          ? { status: 503, body: { status: 503, detail: 'Temporarily unavailable' } }
          : { status: 201, body: { id: 42 } },
    });
    const user = userEvent.setup();
    renderCheckout();

    await user.click(screen.getByRole('button', { name: 'Pay ₹450 (demo payment)' }));
    expect(await screen.findByText(/Temporarily unavailable/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay ₹450 (demo payment)' }));

    expect(await screen.findByRole('heading', { name: 'My bookings page' })).toBeInTheDocument();
    const keys = fetchMock.mock.calls.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys[1]).toBe(keys[0]);
  });

  it('shows the countdown and disables payment once the hold expires', async () => {
    logIn();
    holdStorage.save(holdExpiringIn(-1000));
    renderCheckout();

    expect(screen.getByRole('timer')).toHaveTextContent('0:00');
    expect(screen.getByText('Hold expired')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pay ₹450 (demo payment)' })).toBeDisabled();
    await waitFor(() =>
      expect(screen.getByTestId('announcer-assertive')).toHaveTextContent('Your hold has expired. The seats have been released.'),
    );
  });

  it('counts down from the hold expiry time', () => {
    logIn();
    holdStorage.save(holdExpiringIn(4 * 60_000 + 30_500));
    renderCheckout();

    expect(screen.getByRole('timer')).toHaveTextContent('4:31');
  });
});
