// Shapes of the backend's JSON (see the Swagger UI at /swagger-ui.html).
// Money is always integer paise; times are ISO-8601 strings in UTC.

export type EventType = 'PREMIERE' | 'MATCH';
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export type Role = 'USER' | 'ADMIN';

export interface Venue {
  id: number;
  name: string;
  city: string;
}

export interface EventSummary {
  id: number;
  title: string;
  type: EventType;
  startsAt: string;
  status: 'SCHEDULED' | 'CANCELLED';
  venue: Venue;
}

export interface EventDetail extends EventSummary {
  totalSeats: number;
  availableSeats: number;
  /** Optimistic-locking version; an admin edit must send it back. */
  version: number;
}

export interface UpdateEventRequest {
  venueId: number;
  title: string;
  type: EventType;
  startsAt: string;
  version: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Seat {
  id: number;
  section: string;
  row: string;
  number: number;
  priceTier: string;
  pricePaise: number;
  status: SeatStatus;
}

export interface SeatMap {
  eventId: number;
  seats: Seat[];
}

export interface Hold {
  holdId: string;
  eventId: number;
  expiresAt: string;
  seats: Seat[];
  totalPaise: number;
}

export interface User {
  id: number;
  email: string;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  user: User;
}

export interface BookedSeat {
  id: number;
  section: string;
  row: string;
  number: number;
  priceTier: string;
  pricePaise: number;
}

export interface Booking {
  id: number;
  status: 'CONFIRMED' | 'CANCELLED';
  totalPaise: number;
  createdAt: string;
  holdId: string;
  event: { id: number; title: string; startsAt: string; venueName: string };
  seats: BookedSeat[];
}

/** RFC 7807 error body returned by every failing API call. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Field -> message, for 400 validation errors. */
  errors?: Record<string, string>;
  /** Seats that were taken, for a 409 on a hold. */
  unavailableSeatIds?: number[];
}

export interface CreateEventRequest {
  venueId: number;
  title: string;
  type: EventType;
  startsAt: string;
  layout: {
    sections: {
      name: string;
      rows: number;
      seatsPerRow: number;
      tiers: { name: string; pricePaise: number; fromRow: number; toRow: number }[];
    }[];
  };
}
