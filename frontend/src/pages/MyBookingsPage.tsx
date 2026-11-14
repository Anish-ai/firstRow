import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useLocation } from 'react-router';
import { api } from '../api/endpoints';
import type { Booking } from '../api/types';
import { useAnnounce } from '../components/Announcer';
import { ErrorMessage, Loading } from '../components/Status';
import { dateParts, formatDateTime, formatPaise, seatName } from '../lib/format';
import { usePageTitle } from '../lib/usePageTitle';

export function MyBookingsPage() {
  usePageTitle('My bookings');
  const justBookedId = (useLocation().state as { justBookedId?: number } | null)?.justBookedId;
  const queryClient = useQueryClient();
  const announce = useAnnounce();
  // Captured once per visit (render must stay pure); only used to hide "Cancel" for past events.
  const [loadedAt] = useState(() => Date.now());
  const bookings = useQuery({ queryKey: ['bookings', 'me'], queryFn: api.myBookings });

  const cancel = useMutation({
    mutationFn: (id: number) => api.cancelBooking(id),
    onSuccess: (booking) => {
      announce(`Booking for ${booking.event.title} cancelled. The seats are free again.`);
      void queryClient.invalidateQueries({ queryKey: ['bookings', 'me'] });
    },
    onError: (err) => announce(err.message, 'assertive'),
  });

  return (
    <>
      <p className="eyebrow">Your tickets</p>
      <h1 className="mt-2 text-4xl font-semibold">My bookings</h1>
      {justBookedId && (
        <div role="status" className="alert-success mt-6 p-4 text-base">
          Booking #{justBookedId} confirmed. Enjoy the show!
        </div>
      )}
      {cancel.isError && <div className="mt-4"><ErrorMessage error={cancel.error} /></div>}
      <div className="mt-8">
        {bookings.isPending && <Loading what="your bookings" />}
        {bookings.isError && <ErrorMessage error={bookings.error} />}
        {bookings.data?.length === 0 && (
          <p className="text-muted">
            No bookings yet.{' '}
            <Link to="/" className="text-link">
              Find an event
            </Link>
          </p>
        )}
        <ul className="space-y-4">
          {bookings.data?.map((booking) => (
            <BookingCard
              key={booking.id}
              booking={booking}
              highlighted={booking.id === justBookedId}
              upcoming={new Date(booking.event.startsAt).getTime() > loadedAt}
              cancelling={cancel.isPending && cancel.variables === booking.id}
              onCancel={() => {
                if (window.confirm(`Cancel your booking for ${booking.event.title}?`)) cancel.mutate(booking.id);
              }}
            />
          ))}
        </ul>
      </div>
    </>
  );
}

function BookingCard({
  booking,
  highlighted,
  upcoming,
  cancelling,
  onCancel,
}: {
  booking: Booking;
  highlighted: boolean;
  upcoming: boolean;
  cancelling: boolean;
  onCancel: () => void;
}) {
  const cancelled = booking.status === 'CANCELLED';
  const when = dateParts(booking.event.startsAt);
  return (
    <li
      className={`card flex overflow-hidden ${highlighted ? 'ring-2 ring-accent-bright ring-offset-2 ring-offset-paper' : ''} ${cancelled ? 'opacity-70' : ''}`}
    >
      <div
        aria-hidden="true"
        className={`hidden w-24 shrink-0 flex-col items-center justify-center text-center sm:flex ${cancelled ? 'bg-line text-muted' : 'bg-sand text-cocoa'}`}
      >
        <span className="text-[11px] font-semibold uppercase tracking-[0.2em]">{when.month}</span>
        <span className="font-display text-4xl font-semibold leading-none">{when.day}</span>
        <span className="mt-1 text-xs">{when.time}</span>
      </div>
      <div className="flex-1 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-xl font-semibold">
              <Link to={`/events/${booking.event.id}`} className="hover:underline hover:decoration-accent-bright">
                {booking.event.title}
              </Link>
            </h2>
            <p className="text-sm text-muted">
              <time dateTime={booking.event.startsAt}>{formatDateTime(booking.event.startsAt)}</time> · {booking.event.venueName}
            </p>
          </div>
          <span
            className={`rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider ${
              cancelled ? 'bg-line text-muted' : 'bg-emerald-100 text-emerald-800'
            }`}
          >
            {cancelled ? 'Cancelled' : 'Confirmed'}
          </span>
        </div>
        <ul className="mt-3 flex flex-wrap gap-2 text-sm">
          {booking.seats.map((seat) => (
            <li key={seat.id} className="rounded-full border border-line bg-paper px-3 py-1">
              {seatName(seat)}
            </li>
          ))}
        </ul>
        <div className="perforation mt-4 flex items-center justify-between pt-3">
          <span className="font-semibold tabular-nums">
            {formatPaise(booking.totalPaise)} <span className="text-sm font-normal text-muted">· Booking #{booking.id}</span>
          </span>
          {!cancelled && upcoming && (
            <button
              type="button"
              onClick={onCancel}
              disabled={cancelling}
              className="rounded-full border border-red-300 px-3.5 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50"
            >
              {cancelling ? 'Cancelling…' : 'Cancel booking'}
            </button>
          )}
        </div>
      </div>
    </li>
  );
}
