import type { ProblemDetail } from './types';
import { authStore } from '../auth/authStore';

/** Backend base URL, set at build time (e.g. VITE_API_URL=https://firstrow-api.onrender.com). */
export const API_URL: string = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

/** A failed API call. `problem` is the backend's ProblemDetail body. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.problem = problem;
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
}

/**
 * Small fetch wrapper: adds the JWT, sends/parses JSON, and turns any non-2xx response into an ApiError.
 * A 401 while logged in means the token expired or is invalid, so we log out.
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = authStore.getToken();
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new ApiError({
      status: 0,
      title: 'Network error',
      detail: 'Could not reach the server. It may be waking up; please try again in a minute.',
    });
  }

  if (response.status === 401 && token) authStore.clear();

  if (!response.ok) {
    throw new ApiError(await readProblem(response));
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

async function readProblem(response: Response): Promise<ProblemDetail> {
  try {
    const body = (await response.json()) as Partial<ProblemDetail>;
    return { ...body, status: response.status };
  } catch {
    return { status: response.status, title: response.statusText };
  }
}
