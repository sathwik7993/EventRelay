# Coding Standards

> **EventRelay — Reliable Webhook Delivery Platform**
> Java coding standards, static analysis tooling, and code review practices.

---

## 1. Overview

All EventRelay source code adheres to a unified coding standard derived from the **Google Java Style Guide** with project-specific extensions for Spring Boot, database access, and distributed systems patterns. Consistency across modules (`eventrelay-api`, `eventrelay-core`, `eventrelay-dispatcher`, `eventrelay-common`, `eventrelay-dashboard`) is enforced through automated tooling integrated into the CI/CD pipeline.

> [!IMPORTANT]
> Code that does not pass automated style checks will be rejected at the PR level. No exceptions.

---

## 2. Style Guide Baseline

| Aspect | Standard |
|---|---|
| **Base Guide** | [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) |
| **Java Version** | Java 17 (LTS) — use `var`, sealed classes, records, text blocks, pattern matching where appropriate |
| **Formatter** | `google-java-format` v1.19+ |
| **Line Length** | 120 characters (relaxed from Google's 100 for readability in Spring Boot projects) |
| **Indentation** | 2 spaces (no tabs) |
| **Encoding** | UTF-8 everywhere |

---

## 3. Naming Conventions

### 3.1 General Rules

| Element | Convention | Example |
|---|---|---|
| **Packages** | lowercase, dot-separated | `com.eventrelay.dispatcher` |
| **Classes / Interfaces** | PascalCase (nouns) | `WebhookDispatcher`, `TenantService` |
| **Records** | PascalCase (nouns) | `DeliveryAttemptRecord` |
| **Enums** | PascalCase (singular noun) | `DeliveryStatus` |
| **Enum Constants** | UPPER_SNAKE_CASE | `PENDING`, `DELIVERED`, `FAILED_PERMANENTLY` |
| **Methods** | camelCase (verbs) | `dispatchEvent()`, `calculateBackoff()` |
| **Variables** | camelCase | `retryCount`, `targetUrl` |
| **Constants** | UPPER_SNAKE_CASE | `MAX_RETRY_ATTEMPTS`, `DEFAULT_TIMEOUT_MS` |
| **Type Parameters** | Single capital letter or descriptive | `T`, `E`, `EventT` |
| **Test Classes** | `{ClassUnderTest}Test` | `RetryEngineTest` |
| **Integration Tests** | `{Feature}IntegrationTest` | `WebhookDeliveryIntegrationTest` |

### 3.2 Package Structure

All modules follow the base package `com.eventrelay.{module}`:

```
com.eventrelay.api              # REST API module
com.eventrelay.api.controller   # REST controllers
com.eventrelay.api.dto          # Request/response DTOs
com.eventrelay.api.mapper       # DTO ↔ domain mappers
com.eventrelay.api.config       # API-specific Spring config
com.eventrelay.api.exception    # API exception handlers

com.eventrelay.core             # Core domain module
com.eventrelay.core.model       # Domain entities / aggregates
com.eventrelay.core.repository  # Repository interfaces
com.eventrelay.core.service     # Business logic services
com.eventrelay.core.event       # Domain events
com.eventrelay.core.exception   # Domain exceptions

com.eventrelay.dispatcher       # Dispatcher module
com.eventrelay.dispatcher.worker    # SQS consumers / workers
com.eventrelay.dispatcher.delivery  # HTTP delivery logic
com.eventrelay.dispatcher.signing   # HMAC signing
com.eventrelay.dispatcher.retry     # Retry engine
com.eventrelay.dispatcher.circuit   # Circuit breaker

com.eventrelay.common           # Shared utilities
com.eventrelay.common.util      # Utility classes
com.eventrelay.common.crypto    # Cryptographic helpers
com.eventrelay.common.config    # Shared configuration
```

### 3.3 Spring-Specific Naming

| Component | Suffix | Example |
|---|---|---|
| REST Controllers | `Controller` | `EventController` |
| Service Layer | `Service` | `TenantService` |
| Repository | `Repository` | `EventRepository` |
| Configuration | `Config` / `Configuration` | `SqsConfig` |
| Exception Handler | `ExceptionHandler` | `GlobalExceptionHandler` |
| DTO (Request) | `Request` | `CreateSubscriptionRequest` |
| DTO (Response) | `Response` | `EventDeliveryResponse` |
| Mapper | `Mapper` | `EventMapper` |
| Validator | `Validator` | `WebhookUrlValidator` |

---

## 4. Code Formatting

### 4.1 google-java-format Integration

All source code is formatted with `google-java-format`. This is enforced at build time.

**Maven Plugin Configuration:**

```xml
<plugin>
  <groupId>com.spotify.fmt</groupId>
  <artifactId>fmt-maven-plugin</artifactId>
  <version>2.21.1</version>
  <configuration>
    <style>google</style>
    <verbose>true</verbose>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

**Format all files manually:**

```bash
mvn fmt:format
```

### 4.2 Import Ordering

Imports follow Google Java Style ordering, enforced by `google-java-format`:

```java
// 1. Static imports (alphabetical)
import static org.assertj.core.api.Assertions.assertThat;

// 2. Non-static imports (alphabetical)
import com.eventrelay.core.model.Event;
import com.eventrelay.core.service.EventService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
```

> [!NOTE]
> Wildcard imports (`import java.util.*`) are **never** allowed. Configure your IDE to expand wildcards.

### 4.3 Formatting Rules Summary

| Rule | Value |
|---|---|
| Braces | K&R style (opening brace on same line) |
| Switch | Arrow syntax preferred (Java 17+) |
| Blank lines | 1 between methods, 0 between fields of same type |
| Trailing whitespace | Forbidden |
| Final newline | Required |
| Ternary | Single-line if ≤ 120 chars, otherwise multi-line |

---

## 5. Javadoc Requirements

### 5.1 Mandatory Javadoc

Javadoc is **required** on:

- All `public` and `protected` classes, interfaces, enums, and records
- All `public` and `protected` methods
- All `public` constants
- All REST controller endpoints (include HTTP method, path, status codes)
- All configuration properties

### 5.2 Javadoc Format

```java
/**
 * Dispatches webhook events to registered subscriber endpoints.
 *
 * <p>This service pulls events from the SQS queue, signs each request with
 * HMAC-SHA256, and delivers via HTTP POST. Failed deliveries are routed
 * to the retry engine with exponential backoff.
 *
 * @see RetryEngine
 * @see HmacSigner
 * @since 1.0.0
 */
@Service
public class WebhookDispatcher {

  /**
   * Delivers a single event to the specified target URL.
   *
   * <p>The request includes an {@code X-EventRelay-Signature} header containing
   * the HMAC-SHA256 signature of the request body.
   *
   * @param event     the event to deliver; must not be {@code null}
   * @param targetUrl the subscriber's webhook endpoint URL
   * @return the delivery result containing status code and response time
   * @throws DeliveryException if the HTTP request fails with a non-retryable error
   * @throws IllegalArgumentException if {@code event} or {@code targetUrl} is null
   */
  public DeliveryResult deliver(Event event, String targetUrl) {
    // ...
  }
}
```

### 5.3 Controller Javadoc

```java
/**
 * Ingests events from tenants for webhook delivery.
 *
 * <p>Events are validated, persisted to the outbox table within a database
 * transaction, and subsequently picked up by the dispatcher for delivery.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  /**
   * Submits a new event for delivery.
   *
   * <p>The event is written to the outbox table and will be delivered
   * asynchronously to all matching subscriptions.
   *
   * <ul>
   *   <li>{@code 202 Accepted} — event accepted for delivery</li>
   *   <li>{@code 400 Bad Request} — invalid event payload</li>
   *   <li>{@code 401 Unauthorized} — missing or invalid API key</li>
   *   <li>{@code 429 Too Many Requests} — rate limit exceeded</li>
   * </ul>
   *
   * @param request the event submission request
   * @return the accepted event with a unique event ID
   */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public EventResponse submitEvent(@Valid @RequestBody EventRequest request) {
    // ...
  }
}
```

---

## 6. Code Style Guidelines

### 6.1 Use Java 17 Features

```java
// ✅ Records for immutable DTOs
public record DeliveryResult(int statusCode, long responseTimeMs, Instant deliveredAt) {}

// ✅ Sealed interfaces for delivery status hierarchy
public sealed interface DeliveryOutcome
    permits SuccessfulDelivery, FailedDelivery, PermanentFailure {}

// ✅ Pattern matching for instanceof
if (outcome instanceof FailedDelivery failed) {
  retryEngine.schedule(failed.eventId(), failed.nextAttemptAt());
}

// ✅ Text blocks for multi-line strings (SQL, JSON templates)
String sql = """
    SELECT e.id, e.event_type, e.payload
    FROM events e
    JOIN subscriptions s ON e.event_type = s.event_type
    WHERE e.status = 'PENDING'
    ORDER BY e.created_at ASC
    LIMIT :batchSize
    """;

// ✅ Switch expressions
String label = switch (status) {
  case PENDING -> "Awaiting delivery";
  case DELIVERED -> "Successfully delivered";
  case FAILED -> "Delivery failed";
  case DEAD_LETTERED -> "Moved to DLQ";
};
```

### 6.2 Null Safety

```java
// ✅ Use Optional for return types that may be absent
public Optional<Subscription> findById(UUID subscriptionId) { ... }

// ✅ Use @Nullable / @NonNull annotations (Spring or JetBrains)
public void dispatch(@NonNull Event event, @NonNull URI targetUrl) { ... }

// ✅ Validate inputs at public API boundaries
public DeliveryResult deliver(Event event, String targetUrl) {
  Objects.requireNonNull(event, "event must not be null");
  Objects.requireNonNull(targetUrl, "targetUrl must not be null");
  // ...
}

// ❌ Never return null from a method that returns a collection
// Return Collections.emptyList() or List.of() instead
```

### 6.3 Exception Handling

```java
// ✅ Define domain-specific exceptions
public class DeliveryException extends RuntimeException {
  private final UUID eventId;
  private final int statusCode;

  public DeliveryException(UUID eventId, int statusCode, String message) {
    super(message);
    this.eventId = eventId;
    this.statusCode = statusCode;
  }
}

// ✅ Use @ControllerAdvice for REST error handling
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DeliveryException.class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  public ErrorResponse handleDeliveryException(DeliveryException ex) {
    return new ErrorResponse("DELIVERY_FAILED", ex.getMessage());
  }
}

// ❌ Never catch Exception or Throwable broadly
// ❌ Never swallow exceptions silently
// ❌ Never use exceptions for control flow
```

### 6.4 Logging

```java
// ✅ Use SLF4J with structured logging
private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

// ✅ Use parameterized messages (no string concatenation)
log.info("Delivering event [eventId={}, targetUrl={}, attempt={}/{}]",
    event.getId(), targetUrl, attemptNumber, MAX_RETRY_ATTEMPTS);

// ✅ Include relevant context in error logs
log.error("Delivery failed [eventId={}, statusCode={}, responseTimeMs={}]",
    event.getId(), statusCode, responseTimeMs, exception);

// ❌ Never log sensitive data (API keys, secrets, PII)
// ❌ Never use System.out.println
```

---

## 7. Static Analysis Tooling

### 7.1 Tool Matrix

| Tool | Purpose | Enforcement |
|---|---|---|
| **Checkstyle** | Style enforcement (Google checks) | Build fails on violations |
| **SpotBugs** | Bug pattern detection | Build fails on HIGH/MEDIUM priority |
| **PMD** | Code quality rules | Build warns on violations |
| **google-java-format** | Code formatting | Build fails on format violations |
| **JaCoCo** | Code coverage | Build fails if < 80% line coverage |
| **OWASP Dependency-Check** | Vulnerability scanning | Build fails on CVSS ≥ 7.0 |

### 7.2 Checkstyle Configuration

```xml
<!-- pom.xml -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.3.1</version>
  <configuration>
    <configLocation>config/checkstyle/google_checks.xml</configLocation>
    <consoleOutput>true</consoleOutput>
    <failsOnError>true</failsOnError>
    <violationSeverity>warning</violationSeverity>
    <maxAllowedViolations>0</maxAllowedViolations>
    <suppressionsLocation>config/checkstyle/suppressions.xml</suppressionsLocation>
  </configuration>
  <executions>
    <execution>
      <id>validate</id>
      <phase>validate</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

### 7.3 SpotBugs Configuration

```xml
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <version>4.8.3.0</version>
  <configuration>
    <effort>Max</effort>
    <threshold>Medium</threshold>
    <failOnError>true</failOnError>
    <excludeFilterFile>config/spotbugs/exclude.xml</excludeFilterFile>
    <plugins>
      <plugin>
        <groupId>com.h3xstream.findsecbugs</groupId>
        <artifactId>findsecbugs-plugin</artifactId>
        <version>1.13.0</version>
      </plugin>
    </plugins>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

### 7.4 PMD Configuration

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <version>3.21.2</version>
  <configuration>
    <rulesets>
      <ruleset>config/pmd/ruleset.xml</ruleset>
    </rulesets>
    <failOnViolation>false</failOnViolation>
    <printFailingErrors>true</printFailingErrors>
    <targetJdk>17</targetJdk>
    <minimumTokens>100</minimumTokens>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
        <goal>cpd-check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

### 7.5 JaCoCo Coverage Configuration

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.11</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>test</phase>
      <goals><goal>report</goal></goals>
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
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

---

## 8. IDE Configuration

### 8.1 IntelliJ IDEA (Recommended)

**Required Plugins:**
- google-java-format (enable in `Settings → google-java-format`)
- Checkstyle-IDEA (load `config/checkstyle/google_checks.xml`)
- SonarLint (real-time analysis)
- Lombok (if used — prefer records where possible)

**Editor Settings (`Settings → Editor → Code Style → Java`):**

```
Tab size:            2
Indent:              2
Continuation indent: 4
Right margin:        120
Wrap on typing:      Yes
Ensure newline at EOF: Yes
Strip trailing spaces: All
```

**Import Settings (`Settings → Editor → Code Style → Java → Imports`):**

```
Class count to use import with '*':   999
Names count to use static import with '*': 999
Import layout:
  import static all other imports
  <blank line>
  import all other imports
```

**Save Actions (Settings → Tools → Actions on Save):**
- ✅ Reformat code
- ✅ Optimize imports
- ✅ Rearrange entries

**Shared Settings File (.idea/codeStyles/Project.xml):**

This file is committed to the repository so all team members use identical settings.

### 8.2 VS Code

**Required Extensions:**
- Extension Pack for Java (Microsoft)
- Checkstyle for Java
- SonarLint
- EditorConfig for VS Code

**`.editorconfig` (committed to repo root):**

```ini
# EditorConfig — https://editorconfig.org
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2

[*.java]
indent_size = 2
max_line_length = 120

[*.xml]
indent_size = 2

[*.{yml,yaml}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false

[Makefile]
indent_style = tab
```

---

## 9. Pre-Commit Hooks

### 9.1 Git Hooks Setup

The project uses a shell script installed as a Git pre-commit hook to enforce formatting and checks before every commit.

**`scripts/install-hooks.sh`:**

```bash
#!/usr/bin/env bash
set -euo pipefail

HOOK_DIR="$(git rev-parse --git-dir)/hooks"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Installing pre-commit hook..."
cp "${SCRIPT_DIR}/pre-commit" "${HOOK_DIR}/pre-commit"
chmod +x "${HOOK_DIR}/pre-commit"
echo "✅ Pre-commit hook installed successfully."
```

**`scripts/pre-commit`:**

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🔍 Running pre-commit checks..."

# 1. Check formatting
echo "  → Checking code format (google-java-format)..."
if ! mvn fmt:check -q 2>/dev/null; then
  echo "❌ Code formatting violations detected. Run 'mvn fmt:format' to fix."
  exit 1
fi

# 2. Run Checkstyle
echo "  → Running Checkstyle..."
if ! mvn checkstyle:check -q 2>/dev/null; then
  echo "❌ Checkstyle violations detected. Fix before committing."
  exit 1
fi

# 3. Compile
echo "  → Compiling..."
if ! mvn compile -q -DskipTests 2>/dev/null; then
  echo "❌ Compilation failed."
  exit 1
fi

# 4. Run unit tests
echo "  → Running unit tests..."
if ! mvn test -q -Dtest='!*IntegrationTest' 2>/dev/null; then
  echo "❌ Unit tests failed."
  exit 1
fi

echo "✅ All pre-commit checks passed."
```

### 9.2 Maven Enforcer Plugin

Enforce project-wide rules at build time:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.4.1</version>
  <executions>
    <execution>
      <id>enforce</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <requireMavenVersion>
            <version>[3.8.0,)</version>
          </requireMavenVersion>
          <requireJavaVersion>
            <version>[17,)</version>
          </requireJavaVersion>
          <banDuplicatePomDependencyVersions/>
          <requireReleaseDeps>
            <onlyWhenRelease>true</onlyWhenRelease>
          </requireReleaseDeps>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

---

## 10. Code Review Checklist

Every PR must satisfy the following checklist before merge approval:

### 10.1 Correctness

- [ ] Code compiles without warnings
- [ ] All existing tests pass
- [ ] New functionality has corresponding unit tests
- [ ] Edge cases and error paths are tested
- [ ] No `TODO` or `FIXME` comments without a linked issue

### 10.2 Design

- [ ] Single Responsibility Principle followed
- [ ] No God classes or methods (< 30 lines per method recommended)
- [ ] Dependencies injected via constructor (not field injection)
- [ ] Immutability preferred (records, `final` fields, unmodifiable collections)
- [ ] Domain logic lives in `eventrelay-core`, not in controllers

### 10.3 Security

- [ ] No secrets or API keys in code or config files
- [ ] Input validation on all public API endpoints
- [ ] SQL injection prevention (parameterized queries only)
- [ ] HMAC signatures verified on incoming webhook callbacks
- [ ] Sensitive data not logged (API keys, tokens, PII)

### 10.4 Observability

- [ ] Appropriate log levels (DEBUG for flow, INFO for business events, WARN/ERROR for failures)
- [ ] Structured logging with relevant context (event IDs, tenant IDs)
- [ ] Metrics emitted for key operations (delivery latency, retry counts)
- [ ] Health check endpoints functional

### 10.5 Performance

- [ ] No N+1 query patterns
- [ ] Database queries use appropriate indexes
- [ ] Connection pools properly sized
- [ ] No unbounded collections or streams
- [ ] Pagination used for list endpoints

### 10.6 Documentation

- [ ] Public APIs have Javadoc
- [ ] Non-obvious logic has inline comments
- [ ] README or related docs updated if behavior changed
- [ ] API contract changes reflected in OpenAPI spec

---

## 11. Anti-Patterns to Avoid

| Anti-Pattern | Why It's Bad | Alternative |
|---|---|---|
| Field injection (`@Autowired` on fields) | Untestable, hides dependencies | Constructor injection |
| `@SuppressWarnings("unchecked")` without comment | Hides real type issues | Fix the type, or document why suppression is safe |
| Catching `Exception` broadly | Masks bugs | Catch specific exceptions |
| Mutable shared state | Race conditions | Immutable objects, thread-local, or synchronized access |
| String concatenation in logs | Performance overhead | SLF4J parameterized logging |
| Magic numbers | Unreadable | Named constants |
| Returning `null` from collections | NPE risk | Return empty collection |
| Business logic in controllers | Violates layering | Move to service layer |
| Hardcoded URLs/ports | Breaks across environments | Externalized configuration |

---

## 12. Related Documents

- [Project_Structure.md](./Project_Structure.md) — Module layout and dependency management
- [Git_Strategy.md](./Git_Strategy.md) — Branching, commits, and PR workflow
- [Contributing.md](./Contributing.md) — How to contribute to the project
- [Local_Setup.md](./Local_Setup.md) — Development environment setup

---

> **Last Updated:** 2026-07-10
> **Owner:** EventRelay Platform Team
> **Review Cadence:** Quarterly or upon major dependency upgrades
