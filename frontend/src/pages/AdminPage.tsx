import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { CreateEventRequest, EventType, Venue } from '../api/types';
import { useAnnounce } from '../components/Announcer';
import { ErrorMessage } from '../components/Status';
import { rupeesToPaise } from '../lib/format';
import { usePageTitle } from '../lib/usePageTitle';
import { ManageEvents } from './ManageEvents';

interface TierDraft {
  name: string;
  priceRupees: number;
  fromRow: number;
  toRow: number;
}

interface SectionDraft {
  name: string;
  rows: number;
  seatsPerRow: number;
  tiers: TierDraft[];
}

const inputClass = 'field';
const smallInput = 'field px-2 py-1.5 text-sm';

export function AdminPage() {
  usePageTitle('Admin');
  return (
    <>
      <p className="eyebrow">Back office</p>
      <h1 className="mt-2 text-4xl font-semibold">Admin</h1>
      <p className="mt-2 text-muted">Create, edit and cancel events. Seats are generated from the layout.</p>
      <ManageEvents />
      <CreateEventForm />
    </>
  );
}

function CreateEventForm() {
  const announce = useAnnounce();
  const queryClient = useQueryClient();
  // There is no "list venues" endpoint, so offer venues we've seen on events plus ones created here.
  const events = useQuery({ queryKey: ['events', 'admin-venues'], queryFn: () => api.listEvents({ size: 50 }) });
  const [createdVenues, setCreatedVenues] = useState<Venue[]>([]);
  const venues = dedupe([...createdVenues, ...(events.data?.content.map((e) => e.venue) ?? [])]);

  const [venueId, setVenueId] = useState<number | ''>('');
  const [title, setTitle] = useState('');
  const [type, setType] = useState<EventType>('PREMIERE');
  const [startsAt, setStartsAt] = useState('');
  const [sections, setSections] = useState<SectionDraft[]>([
    {
      name: 'A',
      rows: 6,
      seatsPerRow: 14,
      tiers: [
        { name: 'RECLINER', priceRupees: 750, fromRow: 1, toRow: 2 },
        { name: 'GOLD', priceRupees: 450, fromRow: 3, toRow: 6 },
      ],
    },
  ]);

  const create = useMutation({
    mutationFn: (request: CreateEventRequest) => api.createEvent(request),
    onSuccess: (event) => {
      announce(`Event created with ${event.totalSeats} seats.`);
      void queryClient.invalidateQueries({ queryKey: ['events'] });
    },
  });

  function updateSection(index: number, change: Partial<SectionDraft>) {
    setSections((all) => all.map((s, i) => (i === index ? { ...s, ...change } : s)));
  }

  function updateTier(sectionIndex: number, tierIndex: number, change: Partial<TierDraft>) {
    setSections((all) =>
      all.map((s, i) =>
        i === sectionIndex ? { ...s, tiers: s.tiers.map((t, j) => (j === tierIndex ? { ...t, ...change } : t)) } : s,
      ),
    );
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    create.mutate({
      venueId: Number(venueId),
      title,
      type,
      // <input type="datetime-local"> gives local time without a zone; convert to a UTC instant.
      startsAt: startsAt ? new Date(startsAt).toISOString() : '',
      layout: {
        sections: sections.map((s) => ({
          name: s.name,
          rows: s.rows,
          seatsPerRow: s.seatsPerRow,
          tiers: s.tiers.map((t) => ({
            name: t.name,
            pricePaise: rupeesToPaise(t.priceRupees),
            fromRow: t.fromRow,
            toRow: t.toRow,
          })),
        })),
      },
    });
  }

  const errors = create.error instanceof ApiError ? (create.error.problem.errors ?? {}) : {};
  const totalSeats = sections.reduce((sum, s) => sum + s.rows * s.seatsPerRow, 0);

  return (
    <div className="mt-8 grid gap-6 lg:grid-cols-[1fr_20rem]">
      <form onSubmit={onSubmit} className="card space-y-6 p-6 sm:p-8" aria-labelledby="new-event-heading">
        <h2 id="new-event-heading" className="text-2xl font-semibold">
          New event
        </h2>

        {create.isError && (
          <div role="alert" className="alert-error">
            <p>{create.error.message}</p>
            {Object.keys(errors).length > 0 && (
              <ul className="mt-1 list-disc pl-5">
                {Object.entries(errors).map(([field, message]) => (
                  <li key={field}>
                    {field}: {message}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        {create.isSuccess && (
          <div role="status" className="alert-success">
            Created &ldquo;{create.data.title}&rdquo; with {create.data.totalSeats} seats.{' '}
            <Link to={`/events/${create.data.id}`} className="font-medium underline">
              View event
            </Link>
          </div>
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="venue" className="block text-sm font-medium">
              Venue
            </label>
            <select id="venue" required value={venueId} onChange={(e) => setVenueId(Number(e.target.value) || '')} className={inputClass}>
              <option value="">Choose a venue…</option>
              {venues.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}, {v.city}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="type" className="block text-sm font-medium">
              Type
            </label>
            <select id="type" value={type} onChange={(e) => setType(e.target.value as EventType)} className={inputClass}>
              <option value="PREMIERE">Premiere</option>
              <option value="MATCH">Match</option>
            </select>
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="title" className="block text-sm font-medium">
              Title
            </label>
            <input id="title" required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} className={inputClass} />
          </div>
          <div>
            <label htmlFor="startsAt" className="block text-sm font-medium">
              Starts at (your local time)
            </label>
            <input
              id="startsAt"
              type="datetime-local"
              required
              value={startsAt}
              onChange={(e) => setStartsAt(e.target.value)}
              className={inputClass}
            />
          </div>
        </div>

        <fieldset className="space-y-4">
          <legend className="font-display text-xl font-semibold">Seat layout</legend>
          {sections.map((section, si) => (
            <fieldset key={si} className="rounded-xl border border-line bg-paper/60 p-4">
              <legend className="eyebrow px-1">Section {section.name || si + 1}</legend>
              <div className="grid grid-cols-3 gap-3">
                <NumberOrText label="Name" id={`s${si}-name`} value={section.name} onText={(v) => updateSection(si, { name: v.toUpperCase() })} />
                <NumberOrText label="Rows (max 26)" id={`s${si}-rows`} value={section.rows} min={1} max={26} onNumber={(v) => updateSection(si, { rows: v })} />
                <NumberOrText label="Seats per row" id={`s${si}-seats`} value={section.seatsPerRow} min={1} max={50} onNumber={(v) => updateSection(si, { seatsPerRow: v })} />
              </div>
              <p className="mt-3 text-sm font-medium">Price tiers (by row number, 1 = front row)</p>
              {section.tiers.map((tier, ti) => (
                <div key={ti} className="mt-2 grid grid-cols-[1fr_1fr_4.5rem_4.5rem_auto] items-end gap-2">
                  <NumberOrText label="Tier" id={`s${si}t${ti}-name`} value={tier.name} onText={(v) => updateTier(si, ti, { name: v.toUpperCase() })} />
                  <NumberOrText label="Price (₹)" id={`s${si}t${ti}-price`} value={tier.priceRupees} min={0} onNumber={(v) => updateTier(si, ti, { priceRupees: v })} />
                  <NumberOrText label="From row" id={`s${si}t${ti}-from`} value={tier.fromRow} min={1} onNumber={(v) => updateTier(si, ti, { fromRow: v })} />
                  <NumberOrText label="To row" id={`s${si}t${ti}-to`} value={tier.toRow} min={1} onNumber={(v) => updateTier(si, ti, { toRow: v })} />
                  <button
                    type="button"
                    aria-label={`Remove tier ${tier.name || ti + 1} from section ${section.name}`}
                    disabled={section.tiers.length === 1}
                    onClick={() => updateSection(si, { tiers: section.tiers.filter((_, j) => j !== ti) })}
                    className="btn-small"
                  >
                    Remove
                  </button>
                </div>
              ))}
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() =>
                    updateSection(si, {
                      tiers: [...section.tiers, { name: 'STANDARD', priceRupees: 300, fromRow: section.rows, toRow: section.rows }],
                    })
                  }
                  className="btn-small"
                >
                  Add tier
                </button>
                <button
                  type="button"
                  disabled={sections.length === 1}
                  onClick={() => setSections((all) => all.filter((_, i) => i !== si))}
                  className="btn-small"
                >
                  Remove section
                </button>
              </div>
            </fieldset>
          ))}
          <button
            type="button"
            onClick={() =>
              setSections((all) => [
                ...all,
                { name: String.fromCharCode(65 + all.length), rows: 5, seatsPerRow: 10, tiers: [{ name: 'STANDARD', priceRupees: 300, fromRow: 1, toRow: 5 }] },
              ])
            }
            className="btn-small"
          >
            + Add section
          </button>
        </fieldset>

        <button
          type="submit"
          disabled={create.isPending}
          className="btn-primary w-full"
        >
          {create.isPending ? 'Creating…' : `Create event (${totalSeats} seats)`}
        </button>
      </form>

      <CreateVenueForm
        onCreated={(venue) => {
          setCreatedVenues((all) => [venue, ...all]);
          setVenueId(venue.id);
          announce(`Venue ${venue.name} created and selected.`);
        }}
      />
    </div>
  );
}

function CreateVenueForm({ onCreated }: { onCreated: (venue: Venue) => void }) {
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const create = useMutation({
    mutationFn: () => api.createVenue(name, city),
    onSuccess: (venue) => {
      setName('');
      setCity('');
      onCreated(venue);
    },
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate();
      }}
      aria-labelledby="new-venue-heading"
      className="card h-fit space-y-4 p-6"
    >
      <h2 id="new-venue-heading" className="text-2xl font-semibold">
        New venue
      </h2>
      {create.isError && <ErrorMessage error={create.error} />}
      <div>
        <label htmlFor="venue-name" className="block text-sm font-medium">
          Name
        </label>
        <input id="venue-name" required value={name} onChange={(e) => setName(e.target.value)} className={inputClass} />
      </div>
      <div>
        <label htmlFor="venue-city" className="block text-sm font-medium">
          City
        </label>
        <input id="venue-city" required value={city} onChange={(e) => setCity(e.target.value)} className={inputClass} />
      </div>
      <button type="submit" disabled={create.isPending} className="btn-secondary w-full">
        {create.isPending ? 'Creating…' : 'Create venue'}
      </button>
    </form>
  );
}

function NumberOrText(props: {
  label: string;
  id: string;
  value: string | number;
  min?: number;
  max?: number;
  onText?: (value: string) => void;
  onNumber?: (value: number) => void;
}) {
  const numeric = props.onNumber !== undefined;
  return (
    <div>
      <label htmlFor={props.id} className="block text-xs font-medium text-muted">
        {props.label}
      </label>
      <input
        id={props.id}
        type={numeric ? 'number' : 'text'}
        required
        min={props.min}
        max={props.max}
        value={props.value}
        onChange={(e) => (numeric ? props.onNumber?.(Number(e.target.value)) : props.onText?.(e.target.value))}
        className={smallInput}
      />
    </div>
  );
}

function dedupe(venues: Venue[]): Venue[] {
  const seen = new Map<number, Venue>();
  venues.forEach((v) => seen.set(v.id, v));
  return [...seen.values()];
}
