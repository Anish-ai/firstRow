import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { Hold, Seat } from '../api/types';
import { useSession } from '../auth/authStore';
import { useAnnounce } from '../components/Announcer';
import { SeatLegend, SeatMap } from '../components/SeatMap';
import { ErrorMessage, Loading } from '../components/Status';
import { formatCountdown, formatDateTime, formatPaise, seatName } from '../lib/format';
import { holdStorage } from '../lib/holdStorage';
import { usePageTitle } from '../lib/usePageTitle';
import { useSecondsLeft } from '../lib/useSecondsLeft';

export const MAX_SEATS = 6;
/** Seat map polling interval. Simple and good enough here; Server-Sent Events would push changes instead. */
export const SEAT_REFRESH_MS = 3000;

const NO_SEATS = new Set<number>();

export function EventDetailPage() {
  const eventId = Number(useParams().eventId);
  const session = useSession();
  const navigate = useNavigate();
  const announce = useAnnounce();
  const queryClient = useQueryClient();

  /**
   * The user's changes to the selection, tagged with the hold they started from (`base`). When the hold
   * changes (loaded, updated, released, expired) the old edits no longer match and the selection resets
   * to the hold's seats, with no effect needed to sync the two.
   */
  const [edits, setEdits] = useState<{ base: string; ids: Set<number> } | null>(null);
  const [takenIds, setTakenIds] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const event = useQuery({ queryKey: ['event', eventId], queryFn: () => api.getEvent(eventId) });
  const seatMap = useQuery({
    queryKey: ['seats', eventId],
    queryFn: () => api.getSeatMap(eventId),
    refetchInterval: SEAT_REFRESH_MS,
  });
  // The user's own hold for this event (at most one), so going back from checkout shows it instead of
  // making it look like someone else's.
  const myHoldQuery = useQuery({
    queryKey: ['myHold', eventId],
    queryFn: () => api.getMyHold(eventId),
    enabled: session !== null,
  });
  const secondsLeft = useSecondsLeft(myHoldQuery.data?.expiresAt);
  const myHold: Hold | null = session && myHoldQuery.data && secondsLeft > 0 ? myHoldQuery.data : null;
  usePageTitle(event.data?.title ?? 'Event');

  const holdSeatIds = useMemo(() => (myHold ? new Set(myHold.seats.map((s) => s.id)) : NO_SEATS), [myHold]);
  const base = myHold ? `${myHold.holdId}:${[...holdSeatIds].sort((a, b) => a - b).join(',')}` : 'none';
  const selectedIds = edits && edits.base === base ? edits.ids : holdSeatIds;
  const setSelectedIds = (update: (prev: Set<number>) => Set<number>) => setEdits({ base, ids: update(selectedIds) });

  const seats = seatMap.data?.seats ?? [];
  // A seat is selectable if it's free or already in the user's own hold. A seat someone else grabbed since we
  // selected it drops out automatically when the 3-second poll brings its new status.
  const selectable = (s: Seat) => (s.status === 'AVAILABLE' || holdSeatIds.has(s.id)) && !takenIds.has(s.id);
  const selectedSeats = seats.filter((s) => selectedIds.has(s.id) && selectable(s));
  const lostSeats = seats.filter((s) => selectedIds.has(s.id) && !selectable(s));
  const totalPaise = selectedSeats.reduce((sum, s) => sum + s.pricePaise, 0);
  const changed =
    myHold !== null && (selectedSeats.length !== holdSeatIds.size || selectedSeats.some((s) => !holdSeatIds.has(s.id)));

  function onHoldError(err: Error) {
    if (err instanceof ApiError && err.status === 409 && err.problem.unavailableSeatIds) {
      const taken = new Set(err.problem.unavailableSeatIds);
      const names = seats.filter((s) => taken.has(s.id)).map(seatName);
      const message = `Sorry, ${names.join('; ')} ${names.length === 1 ? 'was' : 'were'} just taken by someone else. Please pick another seat.`;
      setTakenIds(taken);
      setSelectedIds((prev) => new Set([...prev].filter((id) => !taken.has(id))));
      setError(message);
      announce(message, 'assertive');
      void queryClient.invalidateQueries({ queryKey: ['seats', eventId] });
      return;
    }
    if (err instanceof ApiError && err.status === 401) {
      navigate('/login', { state: { from: `/events/${eventId}` } });
      return;
    }
    // e.g. "you already have a hold for this event" (made in another tab): show the hold we actually have.
    if (err instanceof ApiError && err.status === 409) {
      void queryClient.invalidateQueries({ queryKey: ['myHold', eventId] });
    }
    const message = err instanceof ApiError ? err.message : 'Could not hold the seats. Please try again.';
    setError(message);
    announce(message, 'assertive');
  }

  const hold = useMutation({
    mutationFn: () => api.createHold(eventId, selectedSeats.map((s) => s.id)),
    onSuccess: (created) => {
      holdStorage.save(created);
      queryClient.setQueryData(['myHold', eventId], created);
      queryClient.setQueryData(['hold', created.holdId], created);
      announce(`${created.seats.length} seats held for 5 minutes. Continue to payment.`);
      navigate(`/checkout/${created.holdId}`);
    },
    onError: onHoldError,
  });

  const update = useMutation({
    mutationFn: (holdId: string) => api.updateHoldSeats(holdId, selectedSeats.map((s) => s.id)),
    onSuccess: (updated) => {
      holdStorage.save(updated);
      queryClient.setQueryData(['myHold', eventId], updated);
      // Checkout caches the hold by id; give it the new seats, or it would show the old ones.
      queryClient.setQueryData(['hold', updated.holdId], updated);
      setEdits(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['seats', eventId] });
      announce(`Hold updated: ${updated.seats.length} ${updated.seats.length === 1 ? 'seat' : 'seats'}. The timer keeps running.`);
    },
    onError: onHoldError,
  });

  const release = useMutation({
    mutationFn: (holdId: string) => api.releaseHold(holdId),
    onSettled: (_data, _err, holdId) => {
      holdStorage.remove(holdId);
      queryClient.removeQueries({ queryKey: ['hold', holdId] });
      setEdits(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['myHold', eventId] });
      void queryClient.invalidateQueries({ queryKey: ['seats', eventId] });
      announce('Your seats have been released.');
    },
  });

  function toggle(seat: Seat) {
    setError(null);
    setTakenIds(new Set());
    if (selectedIds.has(seat.id)) {
      setSelectedIds((prev) => new Set([...prev].filter((id) => id !== seat.id)));
      announce(`Removed ${seatName(seat)}. ${selectedSeats.length - 1} selected.`);
      return;
    }
    if (selectedSeats.length >= MAX_SEATS) {
      const message = `You can select up to ${MAX_SEATS} seats at a time.`;
      setError(message);
      announce(message, 'assertive');
      return;
    }
    setSelectedIds((prev) => new Set(prev).add(seat.id));
    announce(`Selected ${seatName(seat)}, ${formatPaise(seat.pricePaise)}. ${selectedSeats.length + 1} selected.`);
  }

  if (event.isPending) return <Loading what="event" />;
  if (event.isError) return <ErrorMessage error={event.error} />;

  const e = event.data;
  const cancelled = e.status === 'CANCELLED';
  const busy = hold.isPending || update.isPending || release.isPending;
  const seatsWord = (n: number) => `${n} seat${n === 1 ? '' : 's'}`;

  return (
    <>
      <p className="text-sm">
        <Link to="/" className="text-link">
          ← All events
        </Link>
      </p>
      <div className="mt-6 border-b border-line pb-8">
        <p className="eyebrow">{e.type === 'PREMIERE' ? 'Film premiere' : 'Live match'}</p>
        <h1 className="mt-2 max-w-3xl text-4xl font-semibold leading-tight sm:text-5xl">{e.title}</h1>
        <p className="mt-4 flex flex-wrap gap-x-6 gap-y-1 text-muted">
          <span>
            <time dateTime={e.startsAt}>{formatDateTime(e.startsAt)}</time>
          </span>
          <span>
            {e.venue.name}, {e.venue.city}
          </span>
        </p>
      </div>

      {cancelled && (
        <div role="alert" className="alert-warn mt-8">
          This event has been cancelled. Any bookings for it have been cancelled too.
        </div>
      )}

      <div className="mt-8 grid gap-8 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="seats-heading" className="card min-w-0 p-6 sm:p-8">
          <h2 id="seats-heading" className="mb-2 text-2xl font-semibold">
            Choose your seats
          </h2>
          {seatMap.isPending && <Loading what="seat map" />}
          {seatMap.isError && <ErrorMessage error={seatMap.error} />}
          {seatMap.data && (
            <>
              <SeatMap
                seats={seats}
                selectedIds={new Set(selectedSeats.map((s) => s.id))}
                takenIds={takenIds}
                mineIds={holdSeatIds}
                onToggle={toggle}
                stageLabel={e.type === 'PREMIERE' ? 'Screen' : 'Pitch'}
              />
              <div className="mt-8 border-t border-line pt-5">
                <SeatLegend seats={seats} />
              </div>
            </>
          )}
        </section>

        {/* The order summary looks like a ticket stub: dark header, perforated divider, total. */}
        <aside aria-labelledby="summary-heading" className="card h-fit overflow-hidden lg:sticky lg:top-6">
          <div className="bg-ink px-5 py-4 text-surface">
            <h2 id="summary-heading" className="text-xl font-semibold">
              Your selection
            </h2>
            <p className="mt-0.5 text-sm text-sand">
              {selectedSeats.length} of {MAX_SEATS} seats
            </p>
          </div>
          <div className="p-5">
            {myHold && (
              <div className="mb-4 flex items-center justify-between gap-3 rounded-xl border border-accent-bright/40 bg-accent-soft p-3">
                <span className="text-sm text-cocoa">
                  <span className="block font-semibold text-ink">You&apos;re holding {seatsWord(myHold.seats.length)}</span>
                  Ticked seats are yours.
                </span>
                {/* role="timer" with aria-live off: screen readers can read it on demand without constant interruptions. */}
                <span
                  role="timer"
                  aria-live="off"
                  aria-label={`${formatCountdown(secondsLeft)} left on your hold`}
                  className="font-display text-2xl font-semibold tabular-nums text-accent"
                >
                  {formatCountdown(secondsLeft)}
                </span>
              </div>
            )}
            {error && (
              <div role="alert" className="alert-error mb-3">
                {error}
              </div>
            )}
            <div role="status" className="text-sm text-cocoa">
              {lostSeats.length > 0 && !error && `${lostSeats.map(seatName).join('; ')} is no longer available.`}
            </div>
            {selectedSeats.length === 0 ? (
              <p className="text-sm text-muted">No seats selected yet (up to {MAX_SEATS}).</p>
            ) : (
              <ul className="space-y-2 text-sm">
                {selectedSeats.map((seat) => (
                  <li key={seat.id} className="flex justify-between gap-2">
                    <span className="flex items-center gap-2">
                      <span aria-hidden="true" className="h-2 w-2 rounded-full bg-accent-bright" />
                      {seatName(seat)}
                    </span>
                    <span className="tabular-nums text-muted">{formatPaise(seat.pricePaise)}</span>
                  </li>
                ))}
              </ul>
            )}
            <p className="perforation mt-5 flex items-baseline justify-between pt-4">
              <span className="eyebrow">Total</span>
              <span className="font-display text-2xl font-semibold tabular-nums">{formatPaise(totalPaise)}</span>
            </p>

            {cancelled ? null : !session ? (
              <Link to="/login" state={{ from: `/events/${eventId}` }} className="btn-primary mt-5 w-full">
                Log in to hold seats
              </Link>
            ) : !myHold ? (
              <button
                type="button"
                disabled={selectedSeats.length === 0 || busy}
                onClick={() => hold.mutate()}
                className="btn-primary mt-5 w-full"
              >
                {hold.isPending ? 'Holding seats…' : `Hold ${selectedSeats.length || ''} seat${selectedSeats.length === 1 ? '' : 's'} for 5 minutes`}
              </button>
            ) : (
              <div className="mt-5 space-y-2">
                {!changed ? (
                  <Link
                    to={`/checkout/${myHold.holdId}`}
                    onClick={() => holdStorage.save(myHold)}
                    className="btn-primary w-full"
                  >
                    Continue to checkout
                  </Link>
                ) : (
                  <button
                    type="button"
                    disabled={selectedSeats.length === 0 || busy}
                    onClick={() => update.mutate(myHold.holdId)}
                    className="btn-primary w-full"
                  >
                    {update.isPending
                      ? 'Updating hold…'
                      : selectedSeats.length === 0
                        ? 'Select at least one seat'
                        : `Update hold (${seatsWord(selectedSeats.length)})`}
                  </button>
                )}
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => release.mutate(myHold.holdId)}
                  className="btn-secondary w-full"
                >
                  {release.isPending ? 'Releasing…' : 'Release all seats'}
                </button>
                <p className="text-center text-xs text-muted">Changing seats doesn&apos;t restart the timer.</p>
              </div>
            )}
            <p className="mt-3 text-center text-xs text-muted">Seat availability refreshes every few seconds.</p>
          </div>
        </aside>
      </div>
    </>
  );
}
