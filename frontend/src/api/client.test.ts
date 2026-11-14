import { describe, expect, it, vi } from 'vitest';
import { authStore } from '../auth/authStore';
import { logIn, mockFetch } from '../test/utils';
import { apiFetch, ApiError } from './client';

describe('apiFetch', () => {
  it('turns a ProblemDetail response into an ApiError carrying its fields', async () => {
    mockFetch({
      'POST /x': () => ({ status: 409, body: { status: 409, detail: 'Seats taken', unavailableSeatIds: [3, 4] } }),
    });

    const error = await apiFetch('/x', { method: 'POST', body: {} }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).message).toBe('Seats taken');
    expect((error as ApiError).problem.unavailableSeatIds).toEqual([3, 4]);
  });

  it('returns undefined for 204 No Content', async () => {
    mockFetch({ 'DELETE /x': () => ({ status: 204 }) });

    await expect(apiFetch('/x', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('reports a network failure as a friendly error with status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(apiFetch('/x')).rejects.toMatchObject({ status: 0, message: expect.stringContaining('waking up') });
  });

  it('logs the user out when the server rejects their token', async () => {
    logIn();
    mockFetch({ 'GET /x': () => ({ status: 401, body: { status: 401 } }) });

    await expect(apiFetch('/x')).rejects.toBeInstanceOf(ApiError);
    expect(authStore.getSession()).toBeNull();
  });
});
