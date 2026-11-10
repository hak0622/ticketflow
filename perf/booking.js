import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://app:8080';
if (!/^http:\/\/(app|gateway|localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) {
  throw new Error('This script accepts only the disposable app or a loopback test server');
}
const N = Number(__ENV.USERS || 500);
const FLOW = __ENV.MODE === 'flow';
const WARMUP = __ENV.WARMUP === 'true';
const CAPACITY = Number(__ENV.CAPACITY);
const users = new SharedArray('fresh test JWTs', () => {
  const lines = open(__ENV.USERS_FILE || '/results/users.csv').trim().split(/\r?\n/);
  return lines.slice(1).map(line => {
    const [email, userId, token] = line.split(',');
    return { email, userId, token };
  });
});
if (users.length < N || new Set(users.slice(0, N).map(u => u.userId)).size !== N) {
  throw new Error('Insufficient or duplicate users');
}
const attempted = new Counter('booking_attempts');
const completed = new Counter('completed_users');
const booked = new Counter('booked');
const soldOut = new Counter('sold_out');
const duplicate = new Counter('already_booked');
const processing = new Counter('processing');
const errors = new Rate('unexpected_errors');
const bookingDuration = new Trend('booking_duration', true);
const flowDuration = new Trend('flow_duration', true);
const starts = new Trend('user_started_at_ms');
const finishes = new Trend('user_finished_at_ms');

export const options = {
  scenarios: { once: { executor: 'per-vu-iterations', vus: WARMUP ? 20 : N, iterations: WARMUP ? N / 20 : 1,
    maxDuration: '120s', gracefulStop: '5s' } },
  thresholds: {
    unexpected_errors: ['rate==0'], completed_users: [`count==${N}`],
    booking_attempts: [`count==${N}`], booked: [`count==${Math.min(CAPACITY, N)}`],
    sold_out: [`count==${Math.max(0, N - CAPACITY)}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};

export default function () {
  const index = exec.scenario.iterationInTest;
  const user = users[index];
  booked.add(0); soldOut.add(0); duplicate.add(0); processing.add(0);
  const start = Date.now();
  starts.add(start);
  let bad = false;
  const headers = { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json' };
  const root = `${BASE}/api/concerts/${__ENV.CONCERT_ID}`;
  try {
    if (FLOW) {
      const join = http.post(`${root}/queue`, null, { headers, tags: { name: 'queue_join' }, timeout: '60s' });
      if (join.status !== 200) { bad = true; return; }
      let admitted = false;
      const deadline = Date.now() + 90000;
      while (Date.now() < deadline) {
        const poll = http.get(`${root}/queue/me`, { headers, tags: { name: 'queue_poll' }, timeout: '60s' });
        if (poll.status !== 200) { bad = true; return; }
        const body = poll.json();
        if (body.status === 'ADMITTED') { admitted = true; break; }
        if (body.status !== 'QUEUED') { bad = true; return; }
        // Same adaptive interval + ±250ms jitter as the React queue hook.
        sleep(Math.max(100, (body.nextPollMs || 5000) + Math.random() * 500 - 250) / 1000);
      }
      if (!admitted) { bad = true; return; }
    }
    headers['Idempotency-Key'] = `${__ENV.RUN_ID.slice(0, 24)}${String(index + 1).padStart(12, '0')}`;
    attempted.add(1);
    // Each user sends exactly ONE booking POST; 202/network failures are not retried here.
    const response = http.post(`${root}/booking`, null, { headers,
      responseCallback: http.expectedStatuses(200, 409), tags: { name: 'booking' }, timeout: '60s' });
    bookingDuration.add(response.timings.duration);
    let body = {};
    try { body = response.json(); } catch (_) { /* Transport/error body is classified below. */ }
    if (response.status === 200 && body.status === 'BOOKED') booked.add(1);
    else if (CAPACITY < N && response.status === 409 && (body.message || '').includes('정원')) soldOut.add(1);
    else {
      bad = true;
      if (body.status === 'ALREADY_BOOKED') duplicate.add(1);
      if (body.status === 'BOOKING_PROCESSING') processing.add(1);
    }
  } catch (error) {
    bad = true;
    console.error(`user ${user.userId}: ${error.message}`);
  } finally {
    const end = Date.now();
    finishes.add(end); flowDuration.add(end - start); errors.add(bad); completed.add(1);
  }
}

export function handleSummary(data) {
  return { [`/results/${__ENV.OUTPUT}.json`]: JSON.stringify(data, null, 2) };
}
