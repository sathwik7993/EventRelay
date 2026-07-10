# EventRelay — Timeout Handling

This document details the timeout policies, socket-level settings, and visibility synchronization implemented in the EventRelay dispatch engine to prevent hung worker threads and ensure reliable retries.

---

## 1. Network-Level Timeout Settings

Dispatcher workers execute HTTP POST requests to public internet endpoints using configured HTTP clients (e.g., OkHttp or WebClient). To prevent slow or unresponsive receiver endpoints from locking up worker threads, EventRelay enforces strict, non-blocking timeouts:

| Timeout Dimension | Default Value | Purpose | Behavior on Expiry |
|-------------------|---------------|---------|-------------------|
| **Connect Timeout** | `5,000 ms` (5s) | Time allowed to establish the raw TCP connection to the receiver host. | Connection aborted; delivery marked as `FAILED` (scheduled for retry). |
| **Read Timeout** | `30,000 ms` (30s) | Max time allowed between receipt of successive data packets from the receiver. | Socket terminated; delivery marked as `FAILED` (scheduled for retry). |
| **Write Timeout** | `10,000 ms` (10s) | Max time allowed to transmit the request payload to the socket. | Socket aborted; delivery marked as `FAILED` (scheduled for retry). |
| **Overall Timeout** | `45,000 ms` (45s) | Total elapsed time window allowed for the complete request-response cycle. | Thread interrupted; connection aborted; marked as `FAILED`. |

---

## 2. Webhook Client Socket Configuration

In the Dispatcher Worker Spring Boot code, the HTTP client is initialized with pool settings and socket configurations to ensure high performance:

```java
@Configuration
public class WebhookHttpClientConfig {

    @Bean
    public OkHttpClient webhookHttpClient() {
        ConnectionPool connectionPool = new ConnectionPool(
            100,             // Max idle connections in pool
            5,               // Keep-alive duration
            TimeUnit.MINUTES
        );

        return new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false) // Handle retries at application level
            .build();
    }
}
```

---

## 3. SQS Visibility Timeout Synchronization

A critical distributed system challenge in webhook dispatching is **ensuring that a message remains locked in the queue while delivery is in progress**, preventing duplicate processing by other worker threads.

- **The Problem**: If a webhook receiver takes 30 seconds to respond, and the SQS visibility timeout is only 20 seconds, SQS will make the message visible to *other* workers before the first worker finishes, causing **duplicate webhook delivery**.
- **The Sync Formula**: The SQS Visibility Timeout must exceed the maximum possible duration of the delivery pipeline:
  $$\text{SQS Visibility Timeout} \ge \text{HTTP Connect Timeout} + \text{HTTP Read Timeout} + \text{Processing Overhead} + \text{Buffer}$$
  $$\text{SQS Visibility Timeout} \ge 5\text{s} + 30\text{s} + 5\text{s} + 20\text{s} = 60\text{s}$$
- **EventRelay Configuration**: The main delivery SQS queue is configured with a default **Visibility Timeout of 60 seconds**.

### Dynamic Visibility Extension (Heartbeat)
For long-running tasks or endpoints with special SLA exemptions (e.g., matching a `slow-response` subscription tag), the worker thread runs a background heartbeat scheduler.
- Every 20 seconds, if the delivery thread is still blocked on a socket read, the worker sends an SQS API call:
  `ChangeMessageVisibility(QueueUrl, ReceiptHandle, 30)`
- This extends the lock window by another 30 seconds, preventing SQS from releasing the message while delivery is active.

---

## 4. Mitigation for Slow Receivers

Slow receivers (taking 10s-30s to respond) degrade dispatcher throughput by occupying connection pool slots and worker threads. EventRelay applies two mitigation strategies:

1. **Isolation Pools**: Subscriptions flagged as slow (based on historical p95 latency $> 5$ seconds) are routed to a separate, dedicated SQS queue (`eventrelay-delivery-slow`). This guarantees that normal, fast endpoints ($<200\text{ms}$ response times) are never delayed by a few sluggish receivers.
2. **Aggressive Socket Rejection**: Under heavy load, if a tenant's queue starts backing up, the connect and read timeouts are dynamically scaled down by up to $50\%$, reclaiming threads quickly and moving failing messages to retry schedules.
