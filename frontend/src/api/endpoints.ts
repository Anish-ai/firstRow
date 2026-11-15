import { apiFetch } from './client';
import type {
  AuthResponse,
  Booking,
  CreateEventRequest,
  EventDetail,
  EventSummary,
  EventType,
  Hold,
  Page,
  SeatMap,
  UpdateEventRequest,
  Venue,
} from './types';

/** One function per backend endpoint, so components never build URLs themselves. */
export const api = {
  listEvents: (params: { type?: EventType; page?: number; size?: number }) => {
    const query = new URLSearchParams();
    if (params.type) query.set('type', params.type);
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 12));
    return apiFetch<Page<EventSummary>>(`/api/v1/events?${query}`);
  },
  getEvent: (id: number) => apiFetch<EventDetail>(`/api/v1/events/${id}`),
  getSeatMap: (id: number) => apiFetch<SeatMap>(`/api/v1/events/${id}/seats`),

  signup: (email: string, password: string) =>
    apiFetch<AuthResponse>('/api/v1/auth/signup', { method: 'POST', body: { email, password } }),
  login: (email: string, password: string) =>
    apiFetch<AuthResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } }),

  createHold: (eventId: number, seatIds: number[]) =>
    apiFetch<Hold>(`/api/v1/events/${eventId}/holds`, { method: 'POST', body: { seatIds } }),
  releaseHold: (holdId: string) => apiFetch<void>(`/api/v1/holds/${holdId}`, { method: 'DELETE' }),
  /** Your active hold for an event, or undefined if you have none (204). */
  getMyHold: (eventId: number) => apiFetch<Hold | undefined>(`/api/v1/holds/me?eventId=${eventId}`),
  /** One of your holds while it is active (404 once expired, released or booked). */
  getHold: (holdId: string) => apiFetch<Hold>(`/api/v1/holds/${holdId}`),
  /** Replace the seats of your hold. The expiry time does not change. */
  updateHoldSeats: (holdId: string, seatIds: number[]) =>
    apiFetch<Hold>(`/api/v1/holds/${holdId}/seats`, { method: 'PUT', body: { seatIds } }),

  confirmBooking: (holdId: string, idempotencyKey: string) =>
    apiFetch<Booking>('/api/v1/bookings', {
      method: 'POST',
      body: { holdId },
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  myBookings: () => apiFetch<Booking[]>('/api/v1/bookings/me'),
  cancelBooking: (id: number) => apiFetch<Booking>(`/api/v1/bookings/${id}/cancel`, { method: 'POST' }),

  createVenue: (name: string, city: string) =>
    apiFetch<Venue>('/api/v1/admin/venues', { method: 'POST', body: { name, city } }),
  createEvent: (request: CreateEventRequest) =>
    apiFetch<EventDetail>('/api/v1/admin/events', { method: 'POST', body: request }),
  updateEvent: (id: number, request: UpdateEventRequest) =>
    apiFetch<EventDetail>(`/api/v1/admin/events/${id}`, { method: 'PUT', body: request }),
  cancelEvent: (id: number) => apiFetch<EventDetail>(`/api/v1/admin/events/${id}/cancel`, { method: 'POST' }),
};
