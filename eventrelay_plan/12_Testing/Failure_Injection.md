# EventRelay — Failure Injection Framework

This document outlines the design and integration of the Failure Injection Framework in EventRelay, used to simulate flaky webhooks and network drops for testing delivery guarantees.

---

## 1. Why Failure Injection?

To test the retry engine and DLQ paths, we need to test scenarios where receivers are flaky or down.
- Rather than calling real mock servers, EventRelay uses **WireMock** in integration tests to simulate webhook receivers.
- WireMock allows injecting artificial connection delays, HTTP errors, and packet corruptions.

---

## 2. Webhook Failure Injection via WireMock

Below is the JUnit integration test configuration using WireMock to simulate a flaky target server that fails 3 times before succeeding:

```java
public class WebhookFlakinessTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8089);

    @Test
    public void testSuccessfulDeliveryAfterThreeRetries() {
        // Configure WireMock state machine to simulate flakiness
        String scenarioName = "Flaky Endpoint Scenario";
        
        // Attempts 1-3 fail with 503
        wireMockRule.stubFor(post(urlEqualTo("/webhook"))
            .inScenario(scenarioName)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("Attempt 2 Failed")
            .willReturn(aResponse().withStatus(503)));

        wireMockRule.stubFor(post(urlEqualTo("/webhook"))
            .inScenario(scenarioName)
            .whenScenarioStateIs("Attempt 2 Failed")
            .willSetStateTo("Attempt 3 Failed")
            .willReturn(aResponse().withStatus(503)));

        // Attempt 4 succeeds with 200
        wireMockRule.stubFor(post(urlEqualTo("/webhook"))
            .inScenario(scenarioName)
            .whenScenarioStateIs("Attempt 3 Failed")
            .willReturn(aResponse().withStatus(200).withBody("SUCCESS")));

        // Execute dispatch and assert retry attempts
        executeDispatchPipeline();
    }
}
```

---

## 3. Network Toxicity Testing

Using Toxiproxy, we inject connection toxicity parameters:
- **Latency**: Introduce an artificial $5,000\text{ms}$ delay to test connect and read timeouts.
- **Bandwidth Throttling**: Throttle throughput to $10\text{ KB/s}$ to test slow data writes.
- **Packet Reset**: Abruptly close TCP sockets mid-write to simulate receiver server crashes.
- Verification checks that all these network exceptions are successfully caught by the dispatcher worker and scheduled for exponential retry.
