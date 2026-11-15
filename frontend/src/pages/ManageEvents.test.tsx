import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { EventDetail } from '../api/types';
import { mockFetch, renderRoute } from '../test/utils';
import { ManageEvents } from './ManageEvents';

const venue = { id: 1, name: 'Aurora Grand Cinema', city: 'Mumbai' };
const event: EventDetail = {
  id: 5,
  title: 'Starfall — Premiere Night',
  type: 'PREMIERE',
  startsAt: '2030-01-01T15:30:00Z',
  status: 'SCHEDULED',
  venue,
  totalSeats: 84,
  availableSeats: 80,
  version: 3,
};

function mockAdminApi(extra: Parameters<typeof mockFetch>[0] = {}) {
  return mockFetch({
    'GET /api/v1/events': () => ({ status: 200, body: { content: [event], page: 0, size: 50, totalElements: 1, totalPages: 1 } }),
    'GET /api/v1/events/5': () => ({ status: 200, body: event }),
    ...extra,
  });
}

describe('ManageEvents', () => {
  it('edits an event, sending back the version it was loaded with', async () => {
    const fetchMock = mockAdminApi({
      'PUT /api/v1/admin/events/5': () => ({ status: 200, body: { ...event, title: 'Starfall — Gala Night', version: 4 } }),
    });
    const user = userEvent.setup();
    renderRoute(<ManageEvents />, { path: '/admin', url: '/admin' });

    await user.click(await screen.findByRole('button', { name: /Edit/ }));
    const form = await screen.findByRole('form', { name: `Edit ${event.title}` });
    const title = within(form).getByLabelText('Title');
    await user.clear(title);
    await user.type(title, 'Starfall — Gala Night');
    await user.click(within(form).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true));
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(JSON.parse(String(put[1]!.body))).toEqual({
      title: 'Starfall — Gala Night',
      type: 'PREMIERE',
      venueId: 1,
      startsAt: '2030-01-01T15:30:00.000Z',
      version: 3,
    });
  });

  it('explains a conflicting edit and offers to reload', async () => {
    mockAdminApi({
      'PUT /api/v1/admin/events/5': () => ({
        status: 409,
        body: { status: 409, detail: 'This event was changed by someone else. Reload it and try again.' },
      }),
    });
    const user = userEvent.setup();
    renderRoute(<ManageEvents />, { path: '/admin', url: '/admin' });

    await user.click(await screen.findByRole('button', { name: /Edit/ }));
    await user.click(await screen.findByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('This event was changed by someone else. Reload it and try again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload the latest version' })).toBeInTheDocument();
  });
});
