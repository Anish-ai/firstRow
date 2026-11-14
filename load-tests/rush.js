// k6 load test: ~200 users rush the same event at the same moment.
//
// setup():  creates a fresh 200-seat event (admin API) and one account per virtual user,
//           so every run starts from a clean, known state.
// each VU:  loads the seat map, tries to hold 1-4 random AVAILABLE seats; on 201 it pays (with an
//           Idempotency-Key), on 409 it picks again (up to 8 attempts or until sold out).
// Demand (~500 seats) is far above supply (200), so many requests race for the same seats.
//
// Correctness ("zero double bookings") is checked afterwards in SQL: see verify.sql / run.sh.
// Run with ./run.sh (Docker), or: k6 run -e BASE_URL=http://localhost:8080 rush.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 200);
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@firstrow.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin-password-123';
const MAX_ATTEMPTS = 8;

// 409 is an expected, correct outcome in a rush; don't count it as a failed request.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

const holdLatency = new Trend('hold_latency', true);
const confirmLatency = new Trend('confirm_latency', true);
const seatMapLatency = new Trend('seat_map_latency', true);
const holdConflictRate = new Rate('hold_conflict_rate');
const holdsGranted = new Counter('holds_granted');
const holdsConflicted = new Counter('holds_conflicted');
const holdsRateLimited = new Counter('holds_rate_limited');
const bookingsConfirmed = new Counter('bookings_confirmed');
const unexpectedResponses = new Counter('unexpected_responses');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '5m' },
  },
  thresholds: {
    // The test fails if the server ever answers something other than the expected codes.
    unexpected_responses: ['count==0'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = (token) => ({
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
});

export function setup() {
  const login = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }), json());
  if (login.status !== 200) throw new Error(`admin login failed: ${login.status} ${login.body}`);
  const adminToken = login.json('accessToken');

  const venueId = http.get(`${BASE_URL}/api/v1/events?size=1`).json('content.0.venue.id');
  const runId = Date.now();
  const event = http.post(
    `${BASE_URL}/api/v1/admin/events`,
    JSON.stringify({
      venueId,
      title: `Load Test Rush ${runId}`,
      type: 'MATCH',
      startsAt: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
      layout: {
        sections: [
          {
            name: 'A',
            rows: 10,
            seatsPerRow: 20,
            tiers: [
              { name: 'PAVILION', pricePaise: 150000, fromRow: 1, toRow: 3 },
              { name: 'STAND', pricePaise: 60000, fromRow: 4, toRow: 10 },
            ],
          },
        ],
      },
    }),
    json(adminToken),
  );
  if (event.status !== 201) throw new Error(`event creation failed: ${event.status} ${event.body}`);

  // One account per virtual user, created in parallel batches.
  const tokens = [];
  for (let start = 0; start < VUS; start += 25) {
    const batch = [];
    for (let i = start; i < Math.min(start + 25, VUS); i++) {
      batch.push(['POST', `${BASE_URL}/api/v1/auth/signup`,
        JSON.stringify({ email: `rush-${runId}-${i}@example.com`, password: 'load-test-password' }), json()]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 201) throw new Error(`signup failed: ${res.status} ${res.body}`);
      tokens.push(res.json('accessToken'));
    }
  }
  console.log(`event ${event.json('id')} with ${event.json('totalSeats')} seats, ${tokens.length} users`);
  return { eventId: event.json('id'), tokens };
}

export default function (data) {
  const token = data.tokens[exec.vu.idInTest - 1];

  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const map = http.get(`${BASE_URL}/api/v1/events/${data.eventId}/seats`, { tags: { name: 'GET seat map' } });
    seatMapLatency.add(map.timings.duration);
    const available = map.json('seats').filter((s) => s.status === 'AVAILABLE');
    if (available.length === 0) return; // sold out

    const wanted = pickRandom(available, 1 + Math.floor(Math.random() * 4)).map((s) => s.id);
    const hold = http.post(
      `${BASE_URL}/api/v1/events/${data.eventId}/holds`,
      JSON.stringify({ seatIds: wanted }),
      { ...json(token), tags: { name: 'POST hold' } },
    );
    holdLatency.add(hold.timings.duration);
    check(hold, { 'hold is 201, 409 or 429': (r) => [201, 409, 429].includes(r.status) });

    if (hold.status === 201) {
      holdsGranted.add(1);
      holdConflictRate.add(false);
      const confirm = http.post(
        `${BASE_URL}/api/v1/bookings`,
        JSON.stringify({ holdId: hold.json('holdId') }),
        { headers: { ...json(token).headers, 'Idempotency-Key': crypto.randomUUID() }, tags: { name: 'POST booking' } },
      );
      confirmLatency.add(confirm.timings.duration);
      if (check(confirm, { 'booking is 201': (r) => r.status === 201 })) bookingsConfirmed.add(1);
      else unexpectedResponses.add(1);
      return;
    }
    if (hold.status === 409) {
      holdsConflicted.add(1);
      holdConflictRate.add(true);
      sleep(0.1 + Math.random() * 0.4); // a human glances at the map and picks again
      continue;
    }
    if (hold.status === 429) {
      holdsRateLimited.add(1);
      sleep(Number(hold.headers['Retry-After'] || 1));
      continue;
    }
    unexpectedResponses.add(1);
    console.error(`unexpected hold response ${hold.status}: ${hold.body}`);
    return;
  }
}

function pickRandom(items, count) {
  const copy = items.slice();
  const picked = [];
  while (picked.length < count && copy.length > 0) {
    picked.push(copy.splice(Math.floor(Math.random() * copy.length), 1)[0]);
  }
  return picked;
}
