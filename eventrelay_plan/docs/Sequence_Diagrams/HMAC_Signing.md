# Sequence Diagram — HMAC-SHA256 Request Signing

This document details the sequence of operations for generating and verifying HMAC signatures in EventRelay.

---

## 1. Sequence Diagram (Mermaid)

```mermaid
sequenceDiagram
    autonumber
    participant W as Dispatcher Worker
    participant DB as PostgreSQL DB
    participant R as Webhook Receiver

    W->>DB: Fetch Subscription Signing Secret (whsec_...)
    DB-->>W: Return secret key
    W->>W: Generate current epoch timestamp (t = 1672531199)
    W->>W: Concatenate signing string: "t=1672531199.v1={payload_json}"
    W->>W: Compute HMAC-SHA256 using secret key
    W->>R: HTTP POST Webhook
    Note over W,R: Headers: X-EventRelay-Signature: t=1672531199,v1=computed_hash
    
    rect rgb(240, 240, 240)
        Note over R: Receiver Verification Flow
        R->>R: Extract timestamp & signature from headers
        R->>R: Verify |Current Time - t| <= 5 minutes (Replay block)
        R->>R: Compute expected signature using local tenant secret
        R->>R: Constant-time comparison expected == v1
    end
    
    R-->>W: HTTP 200 OK (Verification Success)
```
---

## 2. Receiver-Side Verification Code Example (Java)

Webhook receivers can verify signature validity:

```java
public boolean verifySignature(String payload, String signatureHeader, String secret) {
    // Parse header: t=...,v1=...
    String[] parts = signatureHeader.split(",");
    String t = parts[0].split("=")[1];
    String v1 = parts[1].split("=")[1];

    // Reconstruct signing string
    String signingString = "t=" + t + ".v1=" + payload;

    // Compute local HMAC
    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    mac.init(secretKey);
    byte[] hash = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
    String expectedSignature = Hex.encodeHexString(hash);

    // Constant-time compare
    return MessageDigest.isEqual(
        expectedSignature.getBytes(StandardCharsets.UTF_8), 
        v1.getBytes(StandardCharsets.UTF_8)
    );
}
```
