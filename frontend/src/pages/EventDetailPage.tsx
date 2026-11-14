import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { Seat } from '../api/types';
import { useSession } from '../auth/authStore';
import { useAnnounce } from '../components/Announcer';
import { SeatLegend, SeatMap } from '../components/SeatMap';
import { ErrorMessage, Loading } from '../components/Status';
import { formatDateTime, formatPaise, seatName } from '../lib/format';
import { holdStorage } from '../lib/holdStorage';
import { usePageTitle } from '../lib/usePageTitle';

export const MAX_SEATS = 6;
/** Seat map polling interval. Simple and good enough here; Server-Sent Events would push changes instead. */
export const SEAT_REFRESH_MS = 3000;

export function EventDetailPage() {
  const eventId = Number(useParams().eventId);
  const session = useSession();
  const navigate = useNavigate();
  const announce = useAnnounce();
  const queryClient = useQueryClient();

  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [takenIds, setTakenIds] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const event = useQuery({ queryKey: ['event', eventId], queryFn: () => api.getEvent(eventId) });
  const seatMap = useQuery({
    queryKey: ['seats', eventId],
    queryFn: () => api.getSeatMap(eventId),
    refetchInterval: SEAT_REFRESH_MS,
  });
  usePageTitle(event.data?.title ?? 'Event');

  const seats = seatMap.data?.seats ?? [];
  // Seats still selectable right now. A seat someone else grabbed since we selected it drops out
  // automatically when the 3-second poll brings its new status.
  const selectedSeats = seats.filter((s) => selectedIds.has(s.id) && s.status === 'AVAILABLE' && !takenIds.has(s.id));
  const lostSeats = seats.filter((s) => selectedIds.has(s.id) && (s.status !== 'AVAILABLE' || takenIds.has(s.id)));
  const totalPaise = selectedSeats.reduce((sum, s) => sum + s.pricePaise, 0);

  const hold = useMutation({
    mutationFn: () => api.createHold(eventId, selectedSeats.map((s) => s.id)),
    onSuccess: (created) => {
      holdStorage.save(created);
      announce(`${created.seats.length} seats held for 5 minutes. Continue to payment.`);
      navigate(`/checkout/${created.holdId}`);
    },
    onError: (err) => {
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
      const message = err instanceof ApiError ? err.message : 'Could not hold the seats. Please try again.';
      setError(message);
      announce(message, 'assertive');
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
            {session ? (
              <button
                type="button"
                disabled={selectedSeats.length === 0 || hold.isPending}
                onClick={() => hold.mutate()}
                className="btn-primary mt-5 w-full"
              >
                {hold.isPending ? 'Holding seats…' : `Hold ${selectedSeats.length || ''} seat${selectedSeats.length === 1 ? '' : 's'} for 5 minutes`}
              </button>
            ) : (
              <Link to="/login" state={{ from: `/events/${eventId}` }} className="btn-primary mt-5 w-full">
                Log in to hold seats
              </Link>
            )}
            <p className="mt-3 text-center text-xs text-muted">Seat availability refreshes every few seconds.</p>
          </div>
        </aside>
      </div>
    </>
  );
}
