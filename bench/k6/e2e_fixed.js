// Ingests a fixed number of events (default 10,000) so end-to-end delivery
// throughput can be measured by timing how long the pipeline takes to drain them.
//
//   docker run --rm -i -e BASE_URL=... -e API_KEY=... -e COUNT=10000 \
//     grafana/k6 run - < bench/k6/e2e_fixed.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const KEY = __ENV.API_KEY;

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    fixed: {
      executor: 'shared-iterations',
      vus: parseInt(__ENV.VUS || '25'),
      iterations: parseInt(__ENV.COUNT || '10000'),
      maxDuration: '5m',
    },
  },
};

export default function () {
  const body = JSON.stringify({
    eventType: 'bench.event',
    data: { vu: __VU, iter: __ITER },
  });
  const res = http.post(`${BASE}/api/v1/events`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
  });
  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
