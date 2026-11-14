# Load test results

Measured with [`rush.js`](rush.js) via [`run.sh`](run.sh) against the final backend image
(JVM flags `-XX:TieredStopAtLevel=1` + CDS archive, see `backend/Dockerfile`). Raw k6 summaries:
[`results/run-1.json`](results/run-1.json), [`run-2.json`](results/run-2.json), [`run-3.json`](results/run-3.json).
Measured on 2026-11-14.

## Scenario

- A fresh event with **200 seats** (10 rows × 20) is created for each run.
- **200 virtual users** start at the same moment. Each loads the seat map, tries to hold 1–4 random available
  seats, and pays on success. On a 409 it picks again (max 8 attempts) until it books or the event sells out.
- Demand (~500 seats) is far above supply (200), so requests constantly race for the same seats.

## Environment

Everything on one laptop: backend, PostgreSQL 16 and k6 all in Docker Desktop.
Intel Core i5-13450HX (10 cores / 16 threads), 23.7 GB RAM; Docker VM with 16 CPUs and 11.5 GB.
Backend: default settings (Hikari pool of 10 connections, JVM heap at 75% of the container).
**Production on Render's free tier (0.1 CPU, 512 MB) will be much slower; these numbers show correctness
under contention and relative cost, not production capacity.**

## Results

| Run | Hold attempts | Holds granted | 409 conflicts | Conflict rate | Hold p50 | Hold p95 | Booking p95 | Seat map p95 | Failed requests |
|---|---|---|---|---|---|---|---|---|---|
| 1 (JVM just started) | 227 | 96 | 131 | 57.7% | 102 ms | 216 ms | 195 ms | 226 ms | 0 |
| 2 | 215 | 96 | 119 | 55.3% | 49 ms | 110 ms | 104 ms | 184 ms | 0 |
| 3 | 216 | 95 | 121 | 56.0% | 46 ms | 88 ms | 89 ms | 154 ms | 0 |

- Every run **sold out all 200 seats** in about 1 s of rush time (1.0 s, 0.9 s, 0.8 s).
- **0 unexpected responses** (every hold answer was 201 or 409; every booking was 201) and **0 failed requests**.
- No 429s: each user stays under the 10 holds/minute rate limit.
- Run 1 is slower because the JVM had just started (no JIT warm-up yet).
- Cross-check: Prometheus counted exactly what k6 counted over the three runs
  (287 holds granted, 371 conflicts, 287 bookings confirmed).
- Earlier image with default JVM flags (same scenario, same laptop): hold p95 316 / 182 / 135 ms.
  Restricting the JIT to C1 and adding a CDS archive made both startup and these runs faster.

## Cold start on a Render-like container

The same image started with Docker limits matching Render's free instance (`--cpus=0.1 --memory=512m`),
time from `docker run` until `/actuator/health` answered:

| Backend image | Cold start | Idle memory |
|---|---|---|
| Default JVM flags | 329 s | 245 MiB / 512 MiB |
| + `-XX:TieredStopAtLevel=1` (C1 JIT only) | 168 s | 208 MiB |
| + CDS archive made at build time (final image) | **114 s** | **176 MiB** |

Docker's CPU limit is a hard cap; Render may allow short bursts, so real cold starts can be shorter.

## Correctness: zero double bookings

After every run, [`verify.sql`](verify.sql) checked the database:

| Check | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Seats in more than one active booking (all events) | 0 | 0 | 0 |
| Seats marked BOOKED / seats in confirmed bookings / distinct booked seats | 200 / 200 / 200 | 200 / 200 / 200 | 200 / 200 / 200 |
| Bookings whose total ≠ sum of their seat prices | 0 | 0 | 0 |
| BOOKED seats not owned by a confirmed booking's hold | 0 | 0 | 0 |

## Reproduce

```bash
docker compose -f infra/docker-compose.yml up -d --build
sh load-tests/run.sh            # VUS=100 sh load-tests/run.sh for a lighter run
```
