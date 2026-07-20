// Ingestion load test.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=<key> \
//     grafana/k6 run - < bench/k6/ingest.js
//
// Measures the synchronous ingest path only: auth -> rate limit -> validate ->
// single transaction writing the event and its outbox row. Delivery happens
// asynchronously and is measured separately (see bench/README.md).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const KEY = __ENV.API_KEY;

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    ingest: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '50'),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    // NFR targets from the design docs.
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<60', 'p(99)<100'],
  },
};

export default function () {
  const body = JSON.stringify({
    eventType: 'bench.event',
    data: { vu: __VU, iter: __ITER, ts: Date.now() },
  });

  const res = http.post(`${BASE}/api/v1/events`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    tags: { endpoint: 'ingest' },
  });

  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
