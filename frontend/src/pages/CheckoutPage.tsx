import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import { useAnnounce } from '../components/Announcer';
import { formatCountdown, formatPaise, seatName } from '../lib/format';
import { holdStorage } from '../lib/holdStorage';
import { usePageTitle } from '../lib/usePageTitle';
import { useSecondsLeft } from '../lib/useSecondsLeft';

export function CheckoutPage() {
  usePageTitle('Checkout');
  const holdId = useParams().holdId ?? '';
  const navigate = useNavigate();
  const announce = useAnnounce();
  const [hold] = useState(() => holdStorage.get(holdId));
  const [error, setError] = useState<string | null>(null);
  const secondsLeft = useSecondsLeft(hold?.expiresAt);
  const expired = hold !== null && secondsLeft === 0;

  // Announce only milestones; a timer read out every second would be unbearable.
  const announced = useRef(new Set<string>());
  useEffect(() => {
    const milestone = expired ? 'expired' : secondsLeft === 60 ? '60' : null;
    if (!milestone || announced.current.has(milestone)) return;
    announced.current.add(milestone);
    announce(
      milestone === 'expired' ? 'Your hold has expired. The seats have been released.' : 'One minute left to complete payment.',
      milestone === 'expired' ? 'assertive' : 'polite',
    );
  }, [secondsLeft, expired, announce]);

  const pay = useMutation({
    // The same key is reused for every retry of this checkout, so a double click or a retry after a
    // timeout returns the original booking instead of creating a second one.
    mutationFn: () => api.confirmBooking(holdId, holdStorage.idempotencyKey(holdId)),
    onSuccess: (booking) => {
      holdStorage.remove(holdId);
      announce('Booking confirmed.');
      navigate('/bookings', { state: { justBookedId: booking.id } });
    },
    onError: (err) => {
      const message = err instanceof ApiError ? err.message : 'Payment failed. Please try again.';
      setError(message);
      announce(message, 'assertive');
    },
  });

  const release = useMutation({
    mutationFn: () => api.releaseHold(holdId),
    onSettled: () => {
      holdStorage.remove(holdId);
      announce('Seats released.');
      navigate(hold ? `/events/${hold.eventId}` : '/');
    },
  });

  if (!hold) {
    return (
      <div role="alert" className="alert-warn mx-auto max-w-xl">
        We couldn&apos;t find this hold in this browser tab.{' '}
        <Link to="/" className="text-link">
          Choose seats again
        </Link>
        .
      </div>
    );
  }

  const urgent = !expired && secondsLeft <= 60;
  return (
    <div className="mx-auto max-w-xl">
      <p className="eyebrow">Step 2 of 2</p>
      <h1 className="mt-2 text-4xl font-semibold">Checkout</h1>

      <div
        className={`mt-6 flex items-center justify-between rounded-2xl p-5 ${
          expired ? 'border border-red-300 bg-red-50 text-red-900' : 'bg-ink text-surface'
        }`}
      >
        <span>
          <span className="block font-medium">{expired ? 'Hold expired' : 'Seats held for you'}</span>
          <span className={`text-sm ${expired ? 'text-red-800' : 'text-sand'}`}>
            {expired ? 'The seats have gone back on sale.' : urgent ? 'Less than a minute left.' : 'Pay before the timer runs out.'}
          </span>
        </span>
        {/* role="timer" with aria-live off: screen readers can read it on demand without constant interruptions. */}
        <span
          role="timer"
          aria-live="off"
          aria-label={`${formatCountdown(secondsLeft)} remaining`}
          className={`font-display text-4xl font-semibold tabular-nums ${expired ? '' : urgent ? 'text-accent-bright' : 'text-surface'}`}
        >
          {formatCountdown(secondsLeft)}
        </span>
      </div>

      <section aria-labelledby="order-heading" className="card mt-6 p-6">
        <h2 id="order-heading" className="text-xl font-semibold">
          Your seats
        </h2>
        <ul className="mt-4 space-y-2 text-sm">
          {hold.seats.map((seat) => (
            <li key={seat.id} className="flex justify-between gap-3">
              <span>
                {seatName(seat)} <span className="text-muted">({seat.priceTier.toLowerCase()})</span>
              </span>
              <span className="tabular-nums">{formatPaise(seat.pricePaise)}</span>
            </li>
          ))}
        </ul>
        <p className="perforation mt-5 flex items-baseline justify-between pt-4">
          <span className="eyebrow">Total</span>
          <span className="font-display text-2xl font-semibold tabular-nums">{formatPaise(hold.totalPaise)}</span>
        </p>
      </section>

      {error && (
        <div role="alert" className="alert-error mt-4">
          {error}{' '}
          <Link to={`/events/${hold.eventId}`} className="font-medium underline">
            Back to the seat map
          </Link>
        </div>
      )}

      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <button type="button" disabled={expired || pay.isPending} onClick={() => pay.mutate()} className="btn-primary flex-1 py-3">
          {pay.isPending ? 'Confirming…' : `Pay ${formatPaise(hold.totalPaise)} (demo payment)`}
        </button>
        <button type="button" disabled={release.isPending} onClick={() => release.mutate()} className="btn-secondary py-3">
          Release seats
        </button>
      </div>
      <p className="mt-3 text-center text-xs text-muted">This is a demo: no real payment is taken.</p>
    </div>
  );
}
