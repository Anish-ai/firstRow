import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { vi } from 'vitest';
import { AppProviders } from '../AppProviders';
import { createQueryClient } from '../queryClient';
import { authStore } from '../auth/authStore';
import type { Seat } from '../api/types';

/** Renders `element` at `path` inside the app's providers and an in-memory router. */
export function renderRoute(element: ReactElement, { path, url, extraRoutes = [] }: {
  path: string;
  url: string;
  extraRoutes?: { path: string; element: ReactElement }[];
}) {
  return render(
    <AppProviders queryClient={createQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path={path} element={element} />
          {extraRoutes.map((r) => (
            <Route key={r.path} path={r.path} element={r.element} />
          ))}
        </Routes>
      </MemoryRouter>
    </AppProviders>,
  );
}

export function logIn() {
  authStore.setFromResponse({
    accessToken: 'test-token',
    tokenType: 'Bearer',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    user: { id: 1, email: 'fan@example.com', role: 'USER' },
  });
}

type Handler = (init: RequestInit | undefined) => { status: number; body?: unknown };

/**
 * Replaces global fetch. Routes are "METHOD /path" (query string ignored).
 * Returns the mock so tests can inspect calls.
 */
export function mockFetch(routes: Record<string, Handler>) {
  const mock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const key = `${init?.method ?? 'GET'} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) throw new Error(`Unexpected request: ${key}`);
    const { status, body } = handler(init);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
    });
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

export function seat(id: number, row: string, number: number, status: Seat['status'] = 'AVAILABLE', section = 'A'): Seat {
  return { id, section, row, number, priceTier: 'GOLD', pricePaise: 45000, status };
}
