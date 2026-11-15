import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { EventDetail, EventType, Venue } from '../api/types';
import { useAnnounce } from '../components/Announcer';
import { ErrorMessage, Loading } from '../components/Status';
import { formatDateTime } from '../lib/format';

/** ISO instant -> value for <input type="datetime-local"> in the admin's own time zone ("2026-12-01T19:30"). */
function toLocalInput(iso: string): string {
  const date = new Date(iso);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

/** Admin list of upcoming events, each with an inline edit form. */
export function ManageEvents() {
  const events = useQuery({ queryKey: ['events', 'admin-manage'], queryFn: () => api.listEvents({ size: 50 }) });
  const [editingId, setEditingId] = useState<number | null>(null);
  // There is no "list venues" endpoint, so offer the venues of the listed events.
  const venues = [...new Map((events.data?.content ?? []).map((e) => [e.venue.id, e.venue])).values()];

  return (
    <section aria-labelledby="manage-heading" className="card mt-8 p-6 sm:p-8">
      <h2 id="manage-heading" className="text-2xl font-semibold">
        Manage events
      </h2>
      <p className="mt-1 text-sm text-muted">
        Edit an event&apos;s name, type, date or venue, or cancel it. Seat layouts and prices can&apos;t be changed
        once an event exists.
      </p>
      <div className="mt-5">
        {events.isPending && <Loading what="events" />}
        {events.isError && <ErrorMessage error={events.error} />}
        {events.data?.content.length === 0 && <p className="text-muted">No upcoming events.</p>}
        <ul className="divide-y divide-line">
          {events.data?.content.map((event) => (
            <li key={event.id} className="py-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <Link to={`/events/${event.id}`} className="font-medium hover:underline hover:decoration-accent-bright">
                    {event.title}
                  </Link>
                  <p className="text-sm text-muted">
                    {event.type === 'PREMIERE' ? 'Premiere' : 'Match'} · <time dateTime={event.startsAt}>{formatDateTime(event.startsAt)}</time>{' '}
                    · {event.venue.name}
                  </p>
                </div>
                <button
                  type="button"
                  aria-expanded={editingId === event.id}
                  aria-controls={`edit-event-${event.id}`}
                  onClick={() => setEditingId(editingId === event.id ? null : event.id)}
                  className="btn-small"
                >
                  {editingId === event.id ? 'Close' : 'Edit'}
                  <span className="sr-only"> {event.title}</span>
                </button>
              </div>
              {editingId === event.id && (
                <div id={`edit-event-${event.id}`} className="mt-3">
                  <EditEventLoader eventId={event.id} venues={venues} onDone={() => setEditingId(null)} />
                </div>
              )}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

/** Loads the full event (with its version), then shows the form pre-filled with it. */
function EditEventLoader({ eventId, venues, onDone }: { eventId: number; venues: Venue[]; onDone: () => void }) {
  const event = useQuery({ queryKey: ['event', eventId], queryFn: () => api.getEvent(eventId) });
  if (event.isPending) return <Loading what="event" />;
  if (event.isError) return <ErrorMessage error={event.error} />;
  // Keyed by version: after a save (or a reload after a conflict) the form starts from the fresh data.
  return <EditEventForm key={event.data.version} event={event.data} venues={venues} onDone={onDone} />;
}

function EditEventForm({ event, venues, onDone }: { event: EventDetail; venues: Venue[]; onDone: () => void }) {
  const announce = useAnnounce();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState(event.title);
  const [type, setType] = useState<EventType>(event.type);
  const [startsAt, setStartsAt] = useState(toLocalInput(event.startsAt));
  const [venueId, setVenueId] = useState(event.venue.id);
  const venueOptions = venues.some((v) => v.id === event.venue.id) ? venues : [event.venue, ...venues];
  const prefix = `edit-${event.id}`;

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['events'] });
    void queryClient.invalidateQueries({ queryKey: ['event', event.id] });
  };

  const save = useMutation({
    mutationFn: () =>
      api.updateEvent(event.id, {
        title,
        type,
        venueId,
        // <input type="datetime-local"> gives local time without a zone; convert to a UTC instant.
        startsAt: startsAt ? new Date(startsAt).toISOString() : '',
        version: event.version,
      }),
    onSuccess: (updated) => {
      announce(`Saved changes to ${updated.title}.`);
      refresh();
      onDone();
    },
  });

  const cancelEvent = useMutation({
    mutationFn: () => api.cancelEvent(event.id),
    onSuccess: (cancelled) => {
      announce(`${cancelled.title} has been cancelled.`);
      refresh();
      onDone();
    },
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  const failure = save.error ?? cancelEvent.error;
  const fieldErrors = failure instanceof ApiError ? (failure.problem.errors ?? {}) : {};
  const stale = failure instanceof ApiError && failure.status === 409;

  return (
    <form onSubmit={onSubmit} aria-label={`Edit ${event.title}`} className="rounded-xl border border-line bg-paper/60 p-4">
      {failure && (
        <div role="alert" className="alert-error mb-4">
          <p>{failure.message}</p>
          {Object.keys(fieldErrors).length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {Object.entries(fieldErrors).map(([field, message]) => (
                <li key={field}>
                  {field}: {message}
                </li>
              ))}
            </ul>
          )}
          {stale && (
            <button type="button" onClick={refresh} className="mt-2 font-medium underline">
              Reload the latest version
            </button>
          )}
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label htmlFor={`${prefix}-title`} className="block text-sm font-medium">
            Title
          </label>
          <input id={`${prefix}-title`} required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} className="field" />
        </div>
        <div>
          <label htmlFor={`${prefix}-type`} className="block text-sm font-medium">
            Type
          </label>
          <select id={`${prefix}-type`} value={type} onChange={(e) => setType(e.target.value as EventType)} className="field">
            <option value="PREMIERE">Premiere</option>
            <option value="MATCH">Match</option>
          </select>
        </div>
        <div>
          <label htmlFor={`${prefix}-starts`} className="block text-sm font-medium">
            Starts at (your local time)
          </label>
          <input
            id={`${prefix}-starts`}
            type="datetime-local"
            required
            value={startsAt}
            onChange={(e) => setStartsAt(e.target.value)}
            className="field"
          />
        </div>
        <div className="sm:col-span-2">
          <label htmlFor={`${prefix}-venue`} className="block text-sm font-medium">
            Venue
          </label>
          <select id={`${prefix}-venue`} value={venueId} onChange={(e) => setVenueId(Number(e.target.value))} className="field">
            {venueOptions.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}, {v.city}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <button type="submit" disabled={save.isPending || cancelEvent.isPending} className="btn-primary">
          {save.isPending ? 'Saving…' : 'Save changes'}
        </button>
        <button type="button" onClick={onDone} className="btn-secondary">
          Discard
        </button>
        <button
          type="button"
          disabled={save.isPending || cancelEvent.isPending}
          onClick={() => {
            if (window.confirm(`Cancel "${event.title}"? All its bookings will be cancelled. This can't be undone.`)) {
              cancelEvent.mutate();
            }
          }}
          className="ml-auto rounded-full border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50"
        >
          {cancelEvent.isPending ? 'Cancelling…' : 'Cancel event'}
        </button>
      </div>
    </form>
  );
}
