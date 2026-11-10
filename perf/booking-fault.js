import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const N = Number(__ENV.USERS);
const fixtures = new SharedArray('fault users', () => JSON.parse(open('/results/requests.json')));
const completed = new Counter('completed_users');
const booked = new Counter('booked');
const recovered = new Counter('recovered_failure');
const errors = new Rate('terminal_errors');
const transport = new Counter('transport_errors');
const technical = new Counter('technical_responses');
const duration = new Trend('booking_duration', true);
const userDuration = new Trend('user_duration', true);
const starts = new Trend('user_started_at_ms');
const ends = new Trend('user_finished_at_ms');
const services = ['app', 'app-2', 'app-3'];

export const options = {
  scenarios: { once: { executor: 'per-vu-iterations', vus: N, iterations: 1,
    maxDuration: '150s', gracefulStop: '5s' } },
  thresholds: { completed_users: [`count==${N}`], booked: [`count==${N-1}`],
    recovered_failure: ['count==1'], terminal_errors: ['rate==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const fixture = fixtures[i];
  const burst = Math.floor(N * .7);
  if (i >= burst) sleep(20 + 20 * (i - burst) / Math.max(1, N - burst - 1));
  const started = Date.now();
  starts.add(started);
  let ok = false;
  try {
    for (let attempt = 0; attempt < 20; attempt++) {
      const service = attempt === 0 ? services[i % 3] : services[1 + (i + attempt) % 2];
      const r = http.post(`http://${service}:8080/api/concerts/${__ENV.CONCERT_ID}/booking`, null, {
        headers: { Authorization: `Bearer ${fixture.token}`, 'Idempotency-Key': fixture.id },
        tags: { name: 'booking', instance: service, phase: attempt === 0 ? 'initial' : 'retry' },
        responseCallback: http.expectedStatuses(200, 202, 409), timeout: '60s',
      });
      duration.add(r.timings.duration);
      if (r.status === 0) transport.add(1);
      else if (r.status >= 500) technical.add(1);
      let body = {};
      try { body = r.json(); } catch (_) { /* Network failures are preserved below. */ }
      console.log(JSON.stringify({type: 'response', user_id: fixture.userId, request_id: fixture.id,
        instance: service, attempt, status: r.status, result: body.status || null,
        message: body.message || r.error || null, end_ms: Date.now(), duration_ms: r.timings.duration}));
      if (r.status === 200 && body.status === 'BOOKED') {
        booked.add(1); ok = i !== 0; break;
      }
      if (r.status === 409 && body.status === 'BOOKING_FAILED') {
        recovered.add(1); ok = i === 0; break;
      }
      // The deliberately held request is left exclusively to automatic recovery first.
      // Other lost responses retry the SAME UUID immediately on a surviving app.
      sleep(i === 0 && attempt === 0 ? 40 : 1);
    }
  } finally {
    const end = Date.now();
    ends.add(end); userDuration.add(end - started); completed.add(1); errors.add(!ok);
    console.log(JSON.stringify({type: 'terminal', user_id: fixture.userId, request_id: fixture.id,
      ok, started_at_ms: started, finished_at_ms: end}));
  }
}

export function handleSummary(data) {
  return { '/results/k6-summary.json': JSON.stringify(data, null, 2) };
}
