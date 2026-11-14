# API reference

Base path: `/api/v1`. JSON in and out. Interactive docs with "Try it out": **`/swagger-ui.html`**
(OpenAPI JSON at `/v3/api-docs`).

- **Auth:** `Authorization: Bearer <accessToken>` from signup/login. Tokens last 1 hour.
- **Money:** integer paise (`45000` = ₹450).
- **Times:** ISO-8601 UTC instants (`2026-12-01T18:30:00Z`).
- **Errors:** RFC 7807 `application/problem+json` with `status`, `title`, `detail`, `instance`, `requestId`,
  plus `errors` (field → message) on validation failures and `unavailableSeatIds` on hold conflicts.
- **Tracing:** every response has `X-Request-Id`; send your own (`[A-Za-z0-9._-]{1,64}`) to have it reused.

## Endpoints

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/auth/signup` | – | 201 `AuthResponse` | 400 validation, 409 email taken |
| POST | `/auth/login` | – | 200 `AuthResponse` | 400, 401 invalid email or password |
| GET | `/events?type=&from=&page=&size=` | – | 200 page of events | 400 bad type / size > 50 |
| GET | `/events/{id}` | – | 200 event + seat counts | 404 |
| GET | `/events/{id}/seats` | – | 200 seat map (effective status) | 404 |
| POST | `/events/{id}/holds` | user | 201 hold | 400 (>6 seats, duplicates, seat of another event), 404, 409 taken / event closed, 429 rate limit |
| DELETE | `/holds/{holdId}` | owner | 204 | 404 (not yours / unknown), 409 already finished |
| POST | `/bookings` + `Idempotency-Key` | user | 201 new booking · 200 replay | 400 missing key, 404 hold, 409 hold expired/booked/released, 422 key reused for another hold |
| GET | `/bookings/me` | user | 200 list, newest first | – |
| POST | `/bookings/{id}/cancel` | owner | 200 booking (idempotent) | 404, 409 event already started |
| POST | `/admin/venues` | ADMIN | 201 venue | 400, 401, 403 |
| POST | `/admin/events` | ADMIN | 201 event + `Location` | 400 (invalid layout, past date, unknown venue), 401, 403 |

Outside `/api/v1`: `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus`.

## Examples

### Sign up / log in

```http
POST /api/v1/auth/signup
Content-Type: application/json

{ "email": "fan@example.com", "password": "correct-horse" }
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-14T11:00:00Z",
  "user": { "id": 7, "email": "fan@example.com", "role": "USER" }
}
```

Passwords: 8–72 characters (BCrypt only uses the first 72 bytes). Wrong password and unknown email give the
same 401 and take about the same time (a dummy BCrypt check runs for unknown emails), so the API doesn't reveal which emails have accounts.

### List events

`GET /api/v1/events?type=MATCH&page=0&size=20`: scheduled events starting at or after `from` (default now),
soonest first.

```json
{
  "content": [
    {
      "id": 4,
      "title": "Desert Falcons vs Coastal Kings — Semi-final",
      "type": "MATCH",
      "startsAt": "2026-11-21T15:00:00Z",
      "status": "SCHEDULED",
      "venue": { "id": 2, "name": "Riverside Arena", "city": "Bengaluru" }
    }
  ],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1
}
```

### Seat map

`GET /api/v1/events/2/seats`: `status` is the *effective* status; a hold that has expired shows as `AVAILABLE`
even before the cleanup job runs.

```json
{
  "eventId": 2,
  "seats": [
    { "id": 1, "section": "A", "row": "A", "number": 1, "priceTier": "RECLINER", "pricePaise": 75000, "status": "AVAILABLE" },
    { "id": 13, "section": "A", "row": "A", "number": 2, "priceTier": "RECLINER", "pricePaise": 75000, "status": "HELD" }
  ]
}
```

### Hold seats (5 minutes, all-or-nothing, max 6)

```http
POST /api/v1/events/2/holds
Authorization: Bearer eyJ...
Content-Type: application/json

{ "seatIds": [1, 13] }
```

201:

```json
{
  "holdId": "4537c96c-f91f-4d43-909f-2487fd969b1f",
  "eventId": 2,
  "expiresAt": "2026-11-13T23:21:56.536Z",
  "seats": [ { "id": 1, "section": "A", "row": "A", "number": 1, "priceTier": "RECLINER", "pricePaise": 75000, "status": "HELD" } ],
  "totalPaise": 150000
}
```

409 (nothing was held, not even the free seats in the request):

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Sorry, some of these seats were just taken by someone else.",
  "instance": "/api/v1/events/2/holds",
  "unavailableSeatIds": [13],
  "requestId": "c0c6a8d4-..."
}
```

429 comes with a `Retry-After` header (seconds). Limit: 10 hold requests per minute per user.

### Confirm (mock payment)

```http
POST /api/v1/bookings
Authorization: Bearer eyJ...
Idempotency-Key: 6f1c1a52-8a0e-4f7b-9d1e-0b9a3c2d7e11
Content-Type: application/json

{ "holdId": "4537c96c-f91f-4d43-909f-2487fd969b1f" }
```

201 with `Location: /api/v1/bookings/1`:

```json
{
  "id": 1,
  "status": "CONFIRMED",
  "totalPaise": 150000,
  "createdAt": "2026-11-13T23:17:02.118Z",
  "holdId": "4537c96c-f91f-4d43-909f-2487fd969b1f",
  "event": { "id": 2, "title": "Starfall — Premiere Night", "startsAt": "2026-11-24T19:00:00Z", "venueName": "Aurora Grand Cinema" },
  "seats": [ { "id": 1, "section": "A", "row": "A", "number": 1, "priceTier": "RECLINER", "pricePaise": 75000 } ]
}
```

Sending the **same key again** returns the same body with **200** and `Idempotent-Replayed: true`. Generate
one key per checkout attempt and reuse it for every retry of that attempt.

### Cancel

`POST /api/v1/bookings/1/cancel` → 200 with `"status": "CANCELLED"`. The seats become `AVAILABLE` again.
Cancelling twice returns the cancelled booking; it never frees seats someone else has since booked.

### Create an event (admin)

```http
POST /api/v1/admin/events
Authorization: Bearer <admin token>
Content-Type: application/json

{
  "venueId": 2,
  "title": "Night Owls vs River Otters — Opener",
  "type": "MATCH",
  "startsAt": "2027-01-10T14:00:00Z",
  "layout": {
    "sections": [
      { "name": "EAST", "rows": 10, "seatsPerRow": 30,
        "tiers": [
          { "name": "PAVILION", "pricePaise": 150000, "fromRow": 1, "toRow": 3 },
          { "name": "STAND",    "pricePaise": 60000,  "fromRow": 4, "toRow": 10 }
        ] }
    ]
  }
}
```

Rows are numbered from 1 and labelled A–Z (max 26 rows, 50 seats per row, 10 sections, 5 tiers). Every row
must be covered by exactly one tier, otherwise 400 (e.g. `"Section EAST: row D has no price tier"`). All seats
are inserted in one JDBC batch inside the same transaction as the event.
