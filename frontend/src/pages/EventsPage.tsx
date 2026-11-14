import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/endpoints';
import type { EventSummary, EventType } from '../api/types';
import { dateParts, formatDateTime } from '../lib/format';
import { usePageTitle } from '../lib/usePageTitle';
import { ErrorMessage, Loading } from '../components/Status';

const FILTERS: { label: string; type?: EventType }[] = [
  { label: 'All events' },
  { label: 'Premieres', type: 'PREMIERE' },
  { label: 'Matches', type: 'MATCH' },
];

export function EventsPage() {
  usePageTitle('Upcoming events');
  const [params, setParams] = useSearchParams();
  const type = (params.get('type') as EventType | null) ?? undefined;
  const page = Number(params.get('page') ?? 0);

  const events = useQuery({
    queryKey: ['events', type, page],
    queryFn: () => api.listEvents({ type, page }),
  });

  function go(next: { type?: EventType; page?: number }) {
    const query = new URLSearchParams();
    if (next.type) query.set('type', next.type);
    if (next.page) query.set('page', String(next.page));
    setParams(query);
  }

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-6 border-b border-line pb-8">
        <div className="max-w-xl">
          <p className="eyebrow">Now booking</p>
          <h1 className="mt-2 text-4xl font-semibold leading-tight sm:text-5xl">Upcoming events</h1>
          <p className="mt-3 text-lg text-muted">
            Premieres and live matches. Pick your seats, hold them for five minutes, and they&apos;re yours.
          </p>
        </div>

        <div className="flex rounded-full border border-line bg-surface p-1" role="group" aria-label="Filter events">
          {FILTERS.map((filter) => {
            const active = type === filter.type;
            return (
              <button
                key={filter.label}
                type="button"
                aria-pressed={active}
                onClick={() => go({ type: filter.type })}
                className={`rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
                  active ? 'bg-ink text-surface' : 'text-muted hover:bg-sand/70 hover:text-ink'
                }`}
              >
                {filter.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="mt-8">
        {events.isPending && <Loading what="events" />}
        {events.isError && <ErrorMessage error={events.error} />}
        {events.data && events.data.content.length === 0 && <p className="text-muted">No upcoming events right now.</p>}
        {events.data && events.data.content.length > 0 && (
          <ul className="grid gap-5 md:grid-cols-2">
            {events.data.content.map((event) => (
              <li key={event.id}>
                <EventTicket event={event} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {events.data && events.data.totalPages > 1 && (
        <nav aria-label="Pagination" className="mt-10 flex items-center justify-center gap-4">
          <button type="button" disabled={page === 0} onClick={() => go({ type, page: page - 1 })} className="btn-small">
            ← Previous
          </button>
          <span className="text-sm text-muted">
            Page {page + 1} of {events.data.totalPages}
          </span>
          <button
            type="button"
            disabled={page + 1 >= events.data.totalPages}
            onClick={() => go({ type, page: page + 1 })}
            className="btn-small"
          >
            Next →
          </button>
        </nav>
      )}
    </>
  );
}

/**
 * An event drawn as a paper ticket: a date stub on the left, a perforated edge, details on the right.
 * The whole card is one link; the decorative parts are aria-hidden, so a screen reader hears
 * the title, date and venue once.
 */
function EventTicket({ event }: { event: EventSummary }) {
  const when = dateParts(event.startsAt);
  const premiere = event.type === 'PREMIERE';
  return (
    <Link
      to={`/events/${event.id}`}
      className="group relative flex h-full overflow-hidden rounded-2xl border border-line bg-surface shadow-[0_1px_2px_rgb(60_45_30/0.06)] transition hover:-translate-y-0.5 hover:border-tan hover:shadow-[0_10px_24px_-12px_rgb(60_45_30/0.35)]"
    >
      <div
        aria-hidden="true"
        className={`flex w-24 shrink-0 flex-col items-center justify-center px-2 py-5 text-center ${
          premiere ? 'bg-ink text-sand' : 'bg-sand text-cocoa'
        }`}
      >
        <span className="text-[11px] font-semibold uppercase tracking-[0.2em]">{when.weekday}</span>
        <span className="font-display text-4xl font-semibold leading-none">{when.day}</span>
        <span className="mt-1 text-xs font-semibold uppercase tracking-[0.2em]">{when.month}</span>
      </div>

      {/* Perforation: a dashed edge with two half-circle notches cut out of the ticket. */}
      <div aria-hidden="true" className="relative w-0 border-l-2 border-dashed border-line">
        <span className="absolute -left-[9px] -top-2 h-4 w-4 rounded-full border border-line bg-paper" />
        <span className="absolute -bottom-2 -left-[9px] h-4 w-4 rounded-full border border-line bg-paper" />
      </div>

      <div className="flex min-w-0 flex-1 flex-col p-5">
        <span className="eyebrow flex items-center gap-1.5">
          <TypeIcon premiere={premiere} />
          {premiere ? 'Premiere' : 'Match'}
        </span>
        <h2 className="mt-2 text-xl font-semibold leading-snug text-ink">{event.title}</h2>
        <p className="mt-2 text-sm text-muted">
          <time dateTime={event.startsAt}>{formatDateTime(event.startsAt)}</time>
        </p>
        <p className="text-sm text-muted">
          {event.venue.name}, {event.venue.city}
        </p>
        <span className="mt-auto flex items-center gap-1 pt-4 text-sm font-semibold text-accent" aria-hidden="true">
          Choose seats
          <span className="transition-transform group-hover:translate-x-1">→</span>
        </span>
      </div>
    </Link>
  );
}

function TypeIcon({ premiere }: { premiere: boolean }) {
  return premiere ? (
    // Film frame
    <svg aria-hidden="true" viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.6">
      <rect x="2" y="3" width="12" height="10" rx="1.5" />
      <path d="M5 3v10M11 3v10M2 6.5h3M2 9.5h3M11 6.5h3M11 9.5h3" />
    </svg>
  ) : (
    // Stadium
    <svg aria-hidden="true" viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.6">
      <ellipse cx="8" cy="8" rx="6" ry="4" />
      <ellipse cx="8" cy="8" rx="2.5" ry="1.5" />
    </svg>
  );
}
