# EventRelay — Input Validation Security

This document outlines the security controls, validation frameworks, and data sanitization routines implemented in EventRelay to prevent injections, buffer overflows, and server exploitation.

---

## 1. Input Validation Architecture

To protect EventRelay endpoints from malicious payloads, validation is applied in three stages:

```
[ Request Ingestion ] ──► [ 1. Schema Validation ] ──► [ 2. Java DTO Constraints ]
                                                                   │
                                                                   ▼
                                                        [ 3. SSRF Target Check ]
```

1. **Schema Validation**: Reject requests early if they do not match expected JSON formats or exceed content length.
2. **Java DTO Constraints**: Spring Boot validators inspect payload fields for types, bounds, and lengths.
3. **Target URL Sanitization**: The dispatcher inspects and resolves IP addresses before sending HTTP requests to prevent Server-Side Request Forgery.

---

## 2. Ingestion DTO Validation (Spring Boot)

Every client request is validated at the controller layer:

```java
public record EventSubmissionRequest(
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 64, message = "Idempotency key must not exceed 64 characters")
    String idempotencyKey,

    @NotBlank(message = "Event type is required")
    @Pattern(regexp = "^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$", message = "Event type must be in dotted notation")
    String eventType,

    @NotNull(message = "Payload object is required")
    Map<String, Object> payload
) {}
```

---

## 3. Server-Side Request Forgery (SSRF) Prevention

SSRF is a severe risk in systems that make outbound HTTP calls to user-specified URLs. Attackers try to force workers to call internal endpoints (such as `169.254.169.254` or local databases).

EventRelay blocks SSRF by verifying resolved IP addresses before the socket connection is created:

```java
public class WebhookUrlValidator {

    private static final Set<String> PRIVATE_CIDRS = Set.of(
        "127.0.0.0/8",      // Loopback
        "10.0.0.0/8",       // Class A Private
        "172.16.0.0/12",    // Class B Private
        "192.168.0.0/16",   // Class C Private
        "169.254.169.254/32" // AWS Metadata
    );

    public static void validateUrl(String targetUrl) throws MalformedURLException {
        URL url = new URL(targetUrl);
        String host = url.getHost();
        
        try {
            InetAddress address = InetAddress.getByName(host);
            if (isPrivateIp(address)) {
                throw new SecurityException("Webhook target URL resolves to an disallowed private network address.");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not resolve target webhook host.");
        }
    }

    private static boolean isPrivateIp(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }
}
```
---

## 4. SQL Injection Prevention

EventRelay prevents SQL Injection by:
- Adhering to Spring Data JPA which uses parameterized queries (`PreparedStatement`) automatically.
- Rejecting dynamic string concatenation inside custom repository queries.
- Running query analysis tools (SpotBugs/Checkstyle) in CI pipelines to flag non-parameterized queries.
