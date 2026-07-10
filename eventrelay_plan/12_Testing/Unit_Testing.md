# Unit Testing Strategy

> **Document Status:** Living Document · **Last Updated:** 2026-07-10 · **Owner:** Platform Engineering

## 1. Overview

Unit tests form the foundation of EventRelay's quality assurance pyramid. They validate individual classes and methods in isolation — without databases, queues, or network calls — executing in milliseconds and providing immediate feedback during development.

> [!IMPORTANT]
> Unit tests must be **fast** (< 100ms per test), **deterministic** (no flaky results), and **isolated** (no shared state or external dependencies). Any test requiring a real database, Redis, or SQS belongs in integration testing.

---

## 2. Testing Philosophy

### 2.1 Test Behavior, Not Implementation

Tests should assert **observable behavior** — return values, state changes, interactions with collaborators — not internal implementation details. This ensures tests survive refactoring.

```java
// ✅ GOOD — tests observable behavior
@Test
void shouldScheduleRetryWithExponentialBackoff_whenDeliveryFails() {
    DeliveryResult failure = DeliveryResult.failure(503, "Service Unavailable");
    
    RetryDecision decision = retryPolicy.evaluate(failure, attemptNumber(3));
    
    assertThat(decision.shouldRetry()).isTrue();
    assertThat(decision.delaySeconds()).isBetween(25L, 35L); // 30s ± jitter
}

// ❌ BAD — tests internal implementation
@Test
void shouldCallPrivateCalculateDelayMethod() {
    // Reflection-based test on private method — fragile and meaningless
}
```

### 2.2 Arrange-Act-Assert (AAA) Pattern

Every test follows a strict three-phase structure:

```java
@Test
void shouldRejectEventWithMissingType() {
    // Arrange
    EventRequest request = EventRequest.builder()
        .payload("{\"orderId\": 42}")
        .build(); // missing eventType

    // Act
    Set<ConstraintViolation<EventRequest>> violations = validator.validate(request);

    // Assert
    assertThat(violations)
        .hasSize(1)
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("Event type is required");
}
```

### 2.3 One Assertion Concept Per Test

Each test validates one logical concept. Multiple `assertThat` calls are fine if they verify the same concept.

---

## 3. What to Unit Test

### 3.1 Test Coverage Matrix

| Component | Priority | Target Coverage | Key Behaviors to Test |
|---|---|---|---|
| `RetryPolicy` | 🔴 Critical | 95%+ | Backoff calculation, max attempts, jitter bounds |
| `HmacSignatureService` | 🔴 Critical | 95%+ | Signature generation, verification, key rotation |
| `EventValidator` | 🔴 Critical | 90%+ | Schema validation, required fields, payload limits |
| `RateLimiter` | 🔴 Critical | 90%+ | Token bucket logic, refill, burst capacity |
| `CircuitBreaker` | 🔴 Critical | 90%+ | State transitions, thresholds, half-open behavior |
| `IdempotencyKeyGenerator` | 🟡 High | 90%+ | Uniqueness, determinism, format compliance |
| `EventMapper` | 🟡 High | 85%+ | DTO ↔ Entity mapping, null handling |
| `DeliveryAttemptRecorder` | 🟡 High | 85%+ | Status recording, metadata capture |
| `SubscriptionMatcher` | 🟡 High | 85%+ | Event type matching, wildcard support |
| `WebhookPayloadBuilder` | 🟢 Standard | 80%+ | Payload serialization, header construction |
| `TenantContext` | 🟢 Standard | 80%+ | Context propagation, thread-local management |
| Controllers (unit) | 🟢 Standard | 75%+ | Input validation, error response mapping |

### 3.2 Components NOT to Unit Test

- JPA repository interfaces (test via integration tests)
- Spring configuration classes (test via integration tests)
- DTOs / POJOs with no logic (Lombok-generated code)
- Third-party library wrappers with no custom logic

---

## 4. Mocking Strategy

### 4.1 Mockito for Dependencies

Use Mockito to isolate the system under test (SUT) from its collaborators:

```java
@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock
    private WebhookHttpClient httpClient;

    @Mock
    private HmacSignatureService signatureService;

    @Mock
    private RateLimiterService rateLimiter;

    @Mock
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @InjectMocks
    private WebhookDispatcher dispatcher;

    @Test
    void shouldDeliverWebhookWithHmacSignature() {
        // Arrange
        WebhookTarget target = WebhookTarget.builder()
            .url("https://example.com/webhook")
            .secret("whsec_test123")
            .build();
        Event event = TestFixtures.sampleEvent("order.completed");

        when(signatureService.sign(any(), any()))
            .thenReturn("sha256=abc123");
        when(rateLimiter.tryAcquire(any()))
            .thenReturn(true);
        when(httpClient.post(any()))
            .thenReturn(new HttpResponse(200, "OK"));

        // Act
        DeliveryResult result = dispatcher.deliver(target, event);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        verify(httpClient).post(argThat(request -> 
            request.getHeaders().containsKey("X-EventRelay-Signature")
        ));
    }
}
```

### 4.2 Mocking Rules

| Rule | Rationale |
|---|---|
| Mock **interfaces**, not concrete classes | Prevents coupling to implementation |
| Never mock the **SUT** itself | Defeats the purpose of the test |
| Never mock **value objects** (DTOs, domain objects) | Use real instances — they have no side effects |
| Use `@Spy` sparingly | Only when partial mocking is unavoidable |
| Prefer `when().thenReturn()` over `doReturn().when()` | More readable; use `doReturn` only for spies |
| Verify interactions **only when side effects matter** | Don't verify every mock interaction |

### 4.3 Custom Test Doubles

For complex collaborators, prefer hand-written test doubles over Mockito:

```java
/**
 * In-memory rate limiter for testing — always permits.
 */
public class AlwaysPermitRateLimiter implements RateLimiter {
    @Override
    public boolean tryAcquire(TenantId tenantId) {
        return true;
    }

    @Override
    public RateLimitStatus getStatus(TenantId tenantId) {
        return RateLimitStatus.unlimited();
    }
}

/**
 * In-memory rate limiter for testing — always denies.
 */
public class AlwaysDenyRateLimiter implements RateLimiter {
    @Override
    public boolean tryAcquire(TenantId tenantId) {
        return false;
    }

    @Override
    public RateLimitStatus getStatus(TenantId tenantId) {
        return RateLimitStatus.exceeded(Duration.ofSeconds(60));
    }
}
```

---

## 5. Test Naming Conventions

### 5.1 Naming Pattern

```
should<ExpectedBehavior>_when<Condition>
```

Examples:

```java
void shouldReturnHttp429_whenRateLimitExceeded()
void shouldRetryWithExponentialBackoff_whenDeliveryReturns503()
void shouldSendToDlq_whenMaxRetriesExhausted()
void shouldRejectPayload_whenSizeExceeds256KB()
void shouldOpenCircuit_whenFailureRateExceedsThreshold()
void shouldGenerateValidHmacSignature_whenSecretProvided()
```

### 5.2 Test Class Naming

```
<ClassUnderTest>Test.java        — Unit tests
<ClassUnderTest>IntegrationTest.java  — Integration tests (separate module)
```

### 5.3 Nested Test Classes

Use `@Nested` for logical grouping:

```java
class RetryPolicyTest {

    @Nested
    @DisplayName("Backoff Calculation")
    class BackoffCalculation {
        @Test void shouldReturn1SecondDelay_forFirstAttempt() { }
        @Test void shouldReturn5SecondDelay_forSecondAttempt() { }
        @Test void shouldCapDelayAt1Hour_afterAttempt8() { }
    }

    @Nested
    @DisplayName("Retry Eligibility")
    class RetryEligibility {
        @Test void shouldRetry_when5xxStatusCode() { }
        @Test void shouldNotRetry_when4xxStatusCode() { }
        @Test void shouldNotRetry_whenMaxAttemptsExhausted() { }
    }
}
```

---

## 6. Code Coverage Targets

### 6.1 Coverage Thresholds

| Metric | Global Target | Core Logic Target | Enforcement |
|---|---|---|---|
| **Line Coverage** | ≥ 80% | ≥ 90% | CI gate (JaCoCo) |
| **Branch Coverage** | ≥ 75% | ≥ 85% | CI gate (JaCoCo) |
| **Method Coverage** | ≥ 80% | ≥ 90% | Advisory |
| **Mutation Score** | ≥ 70% | ≥ 80% | Nightly build (PIT) |

### 6.2 Core Logic Packages (90%+ Required)

```
com.eventrelay.retry.*
com.eventrelay.security.hmac.*
com.eventrelay.ratelimit.*
com.eventrelay.circuitbreaker.*
com.eventrelay.validation.*
com.eventrelay.dispatch.delivery.*
```

### 6.3 JaCoCo Configuration

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.75</minimum>
                            </limit>
                        </limits>
                    </rule>
                    <!-- Stricter rules for core packages -->
                    <rule>
                        <element>PACKAGE</element>
                        <includes>
                            <include>com.eventrelay.retry.*</include>
                            <include>com.eventrelay.security.hmac.*</include>
                            <include>com.eventrelay.ratelimit.*</include>
                            <include>com.eventrelay.circuitbreaker.*</include>
                        </includes>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.90</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## 7. Test Organization

### 7.1 Mirror Source Structure

```
src/
├── main/java/com/eventrelay/
│   ├── retry/
│   │   ├── RetryPolicy.java
│   │   └── ExponentialBackoffCalculator.java
│   ├── security/
│   │   └── hmac/HmacSignatureService.java
│   └── dispatch/
│       └── WebhookDispatcher.java
└── test/java/com/eventrelay/
    ├── retry/
    │   ├── RetryPolicyTest.java
    │   └── ExponentialBackoffCalculatorTest.java
    ├── security/
    │   └── hmac/HmacSignatureServiceTest.java
    ├── dispatch/
    │   └── WebhookDispatcherTest.java
    └── testutil/
        ├── TestFixtures.java
        ├── EventBuilder.java
        └── assertions/
            └── DeliveryResultAssert.java
```

### 7.2 Test Utilities Package

```java
/**
 * Shared test fixtures for unit tests.
 * Provides pre-built domain objects with sensible defaults.
 */
public final class TestFixtures {

    private TestFixtures() {}

    public static Event sampleEvent(String eventType) {
        return Event.builder()
            .id(UUID.randomUUID())
            .tenantId(TenantId.of("tenant_test"))
            .eventType(eventType)
            .payload("{\"orderId\": 42, \"amount\": 99.99}")
            .idempotencyKey(IdempotencyKey.generate())
            .createdAt(Instant.now())
            .build();
    }

    public static WebhookTarget sampleTarget() {
        return WebhookTarget.builder()
            .id(UUID.randomUUID())
            .tenantId(TenantId.of("tenant_test"))
            .url("https://example.com/webhook")
            .secret("whsec_testsecret123456")
            .active(true)
            .build();
    }

    public static Subscription sampleSubscription(String... eventTypes) {
        return Subscription.builder()
            .id(UUID.randomUUID())
            .targetId(UUID.randomUUID())
            .eventTypes(Set.of(eventTypes))
            .active(true)
            .build();
    }
}
```

---

## 8. JUnit 5 Examples for Core Classes

### 8.1 ExponentialBackoffCalculator Test

```java
@DisplayName("ExponentialBackoffCalculator")
class ExponentialBackoffCalculatorTest {

    private ExponentialBackoffCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ExponentialBackoffCalculator(
            Duration.ofSeconds(1),   // initialDelay
            Duration.ofHours(1),     // maxDelay
            2.0,                     // multiplier
            0.1                      // jitterFactor
        );
    }

    @Nested
    @DisplayName("Delay Calculation")
    class DelayCalculation {

        @ParameterizedTest(name = "attempt {0} → base delay {1}s")
        @CsvSource({
            "1, 1",
            "2, 2",
            "3, 4",
            "4, 8",
            "5, 16",
            "6, 32",
            "7, 64",
            "8, 128",
            "9, 256",
            "10, 512"
        })
        void shouldCalculateExponentialDelay(int attempt, long expectedBaseSeconds) {
            Duration delay = calculator.calculateDelay(attempt);

            long baseMs = expectedBaseSeconds * 1000;
            long jitterRange = (long) (baseMs * 0.1);

            assertThat(delay.toMillis())
                .isBetween(baseMs - jitterRange, baseMs + jitterRange);
        }

        @Test
        void shouldCapDelayAtMaximum() {
            Duration delay = calculator.calculateDelay(50);

            assertThat(delay).isLessThanOrEqualTo(Duration.ofHours(1));
        }

        @Test
        void shouldApplyJitter() {
            Set<Duration> delays = IntStream.range(0, 100)
                .mapToObj(i -> calculator.calculateDelay(3))
                .collect(Collectors.toSet());

            // With jitter, not all delays should be identical
            assertThat(delays.size()).isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        void shouldThrowForZeroAttempt() {
            assertThatThrownBy(() -> calculator.calculateDelay(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attempt number must be positive");
        }

        @Test
        void shouldThrowForNegativeAttempt() {
            assertThatThrownBy(() -> calculator.calculateDelay(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
```

### 8.2 HmacSignatureService Test

```java
@DisplayName("HmacSignatureService")
class HmacSignatureServiceTest {

    private HmacSignatureService signatureService;

    @BeforeEach
    void setUp() {
        signatureService = new HmacSignatureService();
    }

    @Nested
    @DisplayName("Signature Generation")
    class SignatureGeneration {

        @Test
        void shouldGenerateValidHmacSha256Signature() {
            String payload = "{\"event\":\"order.completed\",\"orderId\":42}";
            String secret = "whsec_testsecret123";
            String timestamp = "1672531200";

            String signature = signatureService.sign(payload, secret, timestamp);

            assertThat(signature).startsWith("v1=");
            assertThat(signature).hasSize(2 + 1 + 64); // "v1=" + 64 hex chars
        }

        @Test
        void shouldProduceDeterministicSignatures() {
            String payload = "{\"orderId\":42}";
            String secret = "whsec_test";
            String timestamp = "1672531200";

            String sig1 = signatureService.sign(payload, secret, timestamp);
            String sig2 = signatureService.sign(payload, secret, timestamp);

            assertThat(sig1).isEqualTo(sig2);
        }

        @Test
        void shouldProduceDifferentSignaturesForDifferentSecrets() {
            String payload = "{\"orderId\":42}";
            String timestamp = "1672531200";

            String sig1 = signatureService.sign(payload, "whsec_secret1", timestamp);
            String sig2 = signatureService.sign(payload, "whsec_secret2", timestamp);

            assertThat(sig1).isNotEqualTo(sig2);
        }

        @Test
        void shouldIncludeTimestampInSignedContent() {
            String payload = "{\"orderId\":42}";
            String secret = "whsec_test";

            String sig1 = signatureService.sign(payload, secret, "1672531200");
            String sig2 = signatureService.sign(payload, secret, "1672531201");

            assertThat(sig1).isNotEqualTo(sig2);
        }
    }

    @Nested
    @DisplayName("Signature Verification")
    class SignatureVerification {

        @Test
        void shouldVerifyValidSignature() {
            String payload = "{\"orderId\":42}";
            String secret = "whsec_test";
            String timestamp = "1672531200";

            String signature = signatureService.sign(payload, secret, timestamp);
            boolean valid = signatureService.verify(payload, secret, timestamp, signature);

            assertThat(valid).isTrue();
        }

        @Test
        void shouldRejectTamperedPayload() {
            String secret = "whsec_test";
            String timestamp = "1672531200";

            String signature = signatureService.sign("{\"orderId\":42}", secret, timestamp);
            boolean valid = signatureService.verify("{\"orderId\":99}", secret, timestamp, signature);

            assertThat(valid).isFalse();
        }

        @Test
        void shouldRejectInvalidSignatureFormat() {
            assertThat(signatureService.verify("{}", "secret", "12345", "invalid"))
                .isFalse();
        }

        @Test
        void shouldUseConstantTimeComparison() {
            // This test verifies the method doesn't throw on timing attacks
            // The actual constant-time property is an implementation detail
            String payload = "{\"orderId\":42}";
            String secret = "whsec_test";
            String timestamp = "1672531200";
            String validSig = signatureService.sign(payload, secret, timestamp);

            // Should not throw, even with malformed signatures
            assertThatCode(() ->
                signatureService.verify(payload, secret, timestamp, "v1=0000000000000000000000000000000000000000000000000000000000000000")
            ).doesNotThrowAnyException();
        }
    }
}
```

### 8.3 CircuitBreaker Test

```java
@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;
    private Clock testClock;

    @BeforeEach
    void setUp() {
        testClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        circuitBreaker = new CircuitBreaker(
            CircuitBreakerConfig.builder()
                .failureThreshold(5)
                .successThreshold(3)
                .openDuration(Duration.ofSeconds(60))
                .clock(testClock)
                .build()
        );
    }

    @Nested
    @DisplayName("State Transitions")
    class StateTransitions {

        @Test
        void shouldStartInClosedState() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void shouldOpenAfterFailureThresholdReached() {
            // Record 5 consecutive failures
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }

        @Test
        void shouldRemainClosedBelowFailureThreshold() {
            for (int i = 0; i < 4; i++) {
                circuitBreaker.recordFailure();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void shouldTransitionToHalfOpenAfterOpenDuration() {
            // Open the circuit
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }

            // Advance clock past open duration
            testClock = Clock.offset(testClock, Duration.ofSeconds(61));
            circuitBreaker.setClock(testClock);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        void shouldCloseFromHalfOpenAfterSuccessThreshold() {
            // Open → Half-Open
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }
            testClock = Clock.offset(testClock, Duration.ofSeconds(61));
            circuitBreaker.setClock(testClock);

            // Record 3 successes in half-open
            for (int i = 0; i < 3; i++) {
                circuitBreaker.recordSuccess();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void shouldReOpenFromHalfOpenOnFailure() {
            // Open → Half-Open
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }
            testClock = Clock.offset(testClock, Duration.ofSeconds(61));
            circuitBreaker.setClock(testClock);

            // Fail in half-open
            circuitBreaker.recordFailure();

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }
    }

    @Nested
    @DisplayName("Request Allowance")
    class RequestAllowance {

        @Test
        void shouldAllowRequests_whenClosed() {
            assertThat(circuitBreaker.allowRequest()).isTrue();
        }

        @Test
        void shouldDenyRequests_whenOpen() {
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }

            assertThat(circuitBreaker.allowRequest()).isFalse();
        }

        @Test
        void shouldAllowProbeRequest_whenHalfOpen() {
            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }
            testClock = Clock.offset(testClock, Duration.ofSeconds(61));
            circuitBreaker.setClock(testClock);

            assertThat(circuitBreaker.allowRequest()).isTrue();
        }
    }

    @Nested
    @DisplayName("Failure Counter Reset")
    class FailureCounterReset {

        @Test
        void shouldResetFailureCount_onSuccess() {
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            circuitBreaker.recordSuccess(); // reset

            // Should need 5 more failures to open
            for (int i = 0; i < 4; i++) {
                circuitBreaker.recordFailure();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }
    }
}
```

### 8.4 TokenBucketRateLimiter Test

```java
@DisplayName("TokenBucketRateLimiter")
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter rateLimiter;
    private Clock testClock;

    @BeforeEach
    void setUp() {
        testClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        rateLimiter = new TokenBucketRateLimiter(
            10,     // capacity (max tokens)
            10,     // refillRate (tokens per second)
            testClock
        );
    }

    @Test
    void shouldAllowRequestsWithinLimit() {
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.tryAcquire()).isTrue();
        }
    }

    @Test
    void shouldDenyRequestsExceedingBurstCapacity() {
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire();
        }

        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldRefillTokensOverTime() {
        // Drain all tokens
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire();
        }

        // Advance clock by 1 second → should refill 10 tokens
        testClock = Clock.offset(testClock, Duration.ofSeconds(1));
        rateLimiter.setClock(testClock);

        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    @Test
    void shouldNotExceedMaxCapacityOnRefill() {
        // Advance clock by 10 seconds without consuming
        testClock = Clock.offset(testClock, Duration.ofSeconds(10));
        rateLimiter.setClock(testClock);

        // Should still have only 10 tokens (capacity), not 110
        int consumed = 0;
        while (rateLimiter.tryAcquire()) {
            consumed++;
        }
        assertThat(consumed).isEqualTo(10);
    }

    @Test
    void shouldProvideRemainingTokenCount() {
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();

        assertThat(rateLimiter.getAvailableTokens()).isEqualTo(7);
    }
}
```

### 8.5 EventValidator Test

```java
@DisplayName("EventValidator")
class EventValidatorTest {

    private EventValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EventValidator(
            EventValidatorConfig.builder()
                .maxPayloadSizeBytes(256 * 1024) // 256 KB
                .allowedEventTypePattern("^[a-z]+\\.[a-z_.]+$")
                .build()
        );
    }

    @Nested
    @DisplayName("Valid Events")
    class ValidEvents {

        @Test
        void shouldAcceptValidEvent() {
            EventRequest request = EventRequest.builder()
                .eventType("order.completed")
                .payload("{\"orderId\": 42}")
                .idempotencyKey("idem_abc123")
                .build();

            ValidationResult result = validator.validate(request);

            assertThat(result.isValid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "order.completed",
            "user.created",
            "payment.invoice.paid",
            "subscription.plan.changed"
        })
        void shouldAcceptValidEventTypes(String eventType) {
            EventRequest request = TestFixtures.eventRequestWithType(eventType);

            assertThat(validator.validate(request).isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Invalid Events")
    class InvalidEvents {

        @Test
        void shouldRejectMissingEventType() {
            EventRequest request = EventRequest.builder()
                .payload("{\"orderId\": 42}")
                .build();

            ValidationResult result = validator.validate(request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).contains("eventType is required");
        }

        @Test
        void shouldRejectMissingPayload() {
            EventRequest request = EventRequest.builder()
                .eventType("order.completed")
                .build();

            ValidationResult result = validator.validate(request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).contains("payload is required");
        }

        @Test
        void shouldRejectOversizedPayload() {
            String largePayload = "x".repeat(256 * 1024 + 1);
            EventRequest request = EventRequest.builder()
                .eventType("order.completed")
                .payload(largePayload)
                .build();

            ValidationResult result = validator.validate(request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("payload exceeds maximum size"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"ORDER.COMPLETED", "order completed", "order/completed", ""})
        void shouldRejectInvalidEventTypeFormats(String eventType) {
            EventRequest request = TestFixtures.eventRequestWithType(eventType);

            assertThat(validator.validate(request).isValid()).isFalse();
        }

        @Test
        void shouldRejectInvalidJsonPayload() {
            EventRequest request = EventRequest.builder()
                .eventType("order.completed")
                .payload("{invalid json")
                .build();

            ValidationResult result = validator.validate(request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).contains("payload must be valid JSON");
        }

        @Test
        void shouldCollectMultipleValidationErrors() {
            EventRequest request = EventRequest.builder().build();

            ValidationResult result = validator.validate(request);

            assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(2);
        }
    }
}
```

---

## 9. Custom AssertJ Assertions

Create domain-specific assertions for cleaner test code:

```java
public class DeliveryResultAssert extends AbstractAssert<DeliveryResultAssert, DeliveryResult> {

    private DeliveryResultAssert(DeliveryResult actual) {
        super(actual, DeliveryResultAssert.class);
    }

    public static DeliveryResultAssert assertThat(DeliveryResult actual) {
        return new DeliveryResultAssert(actual);
    }

    public DeliveryResultAssert isSuccessful() {
        isNotNull();
        if (!actual.isSuccess()) {
            failWithMessage("Expected delivery to be successful but got status <%s> with message <%s>",
                actual.getHttpStatus(), actual.getErrorMessage());
        }
        return this;
    }

    public DeliveryResultAssert hasHttpStatus(int expected) {
        isNotNull();
        if (actual.getHttpStatus() != expected) {
            failWithMessage("Expected HTTP status <%d> but was <%d>", expected, actual.getHttpStatus());
        }
        return this;
    }

    public DeliveryResultAssert isRetryable() {
        isNotNull();
        if (!actual.isRetryable()) {
            failWithMessage("Expected delivery result to be retryable");
        }
        return this;
    }
}
```

Usage in tests:

```java
@Test
void shouldMarkServerErrorsAsRetryable() {
    DeliveryResult result = dispatcher.deliver(target, event);

    DeliveryResultAssert.assertThat(result)
        .hasHttpStatus(503)
        .isRetryable();
}
```

---

## 10. Test Execution & Maven Configuration

### 10.1 Maven Surefire Configuration (Unit Tests Only)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <includes>
            <include>**/*Test.java</include>
        </includes>
        <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
            <exclude>**/*E2ETest.java</exclude>
        </excludes>
        <parallel>methods</parallel>
        <threadCount>4</threadCount>
        <reportFormat>xml</reportFormat>
        <argLine>${jacoco.agent.argLine} -Xmx512m</argLine>
    </configuration>
</plugin>
```

### 10.2 Running Tests

```bash
# Run all unit tests
mvn test

# Run a specific test class
mvn test -Dtest=RetryPolicyTest

# Run a specific nested test class
mvn test -Dtest="RetryPolicyTest\$BackoffCalculation"

# Run with coverage report
mvn test jacoco:report

# Run mutation testing (nightly)
mvn pitest:mutationCoverage
```

---

## 11. Production Considerations

> [!TIP]
> - **Treat tests as production code**: Apply the same quality standards — readable, maintainable, well-structured
> - **Run unit tests on every commit**: They should complete in under 30 seconds
> - **Investigate flaky tests immediately**: A flaky test erodes team confidence; quarantine and fix within 24 hours
> - **Review test code in PRs**: Tests are documentation — they describe intended behavior
> - **Use mutation testing monthly**: Coverage numbers lie; mutation testing reveals tests that pass without actually verifying behavior

> [!WARNING]
> **Anti-patterns to avoid:**
> - Testing private methods via reflection
> - Tests with `Thread.sleep()` — use controllable clocks instead
> - Tests that depend on execution order
> - Tests that write to the filesystem or network
> - Asserting on `toString()` output for behavior verification
> - Over-mocking: if you mock more than 3 dependencies, the class may need decomposition

---

## 12. Related Documents

| Document | Relationship |
|---|---|
| [Integration Testing](./Integration_Testing.md) | Tests with real infrastructure |
| [Testcontainers](./Testcontainers.md) | Container-based test infrastructure |
| [CI Test Pipeline](./CI_Test_Pipeline.md) | How tests run in CI |
| [Performance Benchmarks](./Performance_Benchmarks.md) | JMH microbenchmarks |
