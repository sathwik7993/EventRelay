# EventRelay — Load Testing Strategy

This document details the load testing suite, test scenarios, and k6 script configurations used to validate EventRelay's capacity to handle 10,000+ events per second.

---

## 1. Load Testing Scenarios

We execute three load test profiles on EventRelay:

1. **Sustained Load (Smoke Test)**: 1,000 events/sec for 1 hour. Confirms memory stability and connection pool resilience.
2. **Burst Test (Stress Test)**: Immediate spike from 100 to 10,000 events/sec over 30 seconds. Confirms queue autoscaling and backpressure capabilities.
3. **Soak Test (Longevity)**: 2,500 events/sec sustained over 24 hours. Identifies memory leaks, database index bloat, and cache fragmentation.

---

## 2. Ingestion Load Test Script (k6)

Below is the JavaScript k6 load test script targetting the event ingestion endpoint:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '1m', target: 1000 },  // Ramp up to 1,000 users
    { duration: '5m', target: 5000 },  // Ramp up to 5,000 users
    { duration: '2m', target: 0 },     // Ramp down to 0
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],    // Error rate must be less than 1%
    http_req_duration: ['p99<100'],    // 99% of requests must complete under 100ms
  },
};

export default function () {
  const url = 'https://api.eventrelay.internal/api/v1/events';
  const payload = JSON.stringify({
    event_type: 'payment.succeeded',
    payload: {
      id: `evt_${uuidv4()}`,
      amount: Math.floor(Math.random() * 10000),
      currency: 'USD',
    },
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-EventRelay-Key': 'er_live_7d5e6a8b1c2d3e4f5g6h7i8j9k0l1m2n',
      'X-Idempotency-Key': `idemp-${uuidv4()}`,
    },
  };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 202': (r) => r.status === 202,
  });

  sleep(0.1); // Delay between virtual user requests
}
```

---

## 3. Results Analysis Checklist

During and after load test runs, verify the following metrics:
- **JVM Heap Memory**: Stable horizontal line on Prometheus charts; no garbage collection pauses $> 100\text{ms}$.
- **Connection Pools**: Active database connections must remain below $80\%$ of maximum capacity.
- **Queue Backlog**: Queue depth must return to $0$ within 5 minutes of load ramp-down.
