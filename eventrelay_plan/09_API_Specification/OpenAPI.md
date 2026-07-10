# EventRelay — OpenAPI 3.0 Specification

This document details the OpenAPI 3.0 YAML definition structure for the EventRelay API, enabling automatic interactive documentation and client SDK generation.

---

## 1. Hosting Swagger UI

EventRelay integrates interactive API documentation in non-production environments using **springdoc-openapi**:

- **Local Endpoint**: `http://localhost:8080/swagger-ui.html`
- **JSON Specification**: `http://localhost:8080/v3/api-docs`

To secure documentation in staging and production, Swagger UI is placed behind basic auth and only exposes public-facing endpoints (excluding internal dashboard APIs).

---

## 2. Interactive Schema Definitions (YAML)

Below is the core structure of the OpenAPI 3.0 YAML specification:

```yaml
openapi: 3.0.3
info:
  title: EventRelay Ingestion and Management API
  description: Reliable Webhook Delivery Platform API documentation.
  version: 1.0.0
servers:
  - url: https://api.eventrelay.io/api/v1
paths:
  /events:
    post:
      summary: Submit a new event for delivery
      security:
        - ApiKeyAuth: []
      parameters:
        - in: header
          name: X-Idempotency-Key
          required: true
          schema:
            type: string
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/EventSubmission'
      responses:
        '202':
          description: Event accepted and queued.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/IngestionReceipt'
        '429':
          description: Rate limit exceeded.

components:
  securitySchemes:
    ApiKeyAuth:
      type: apiKey
      in: header
      name: X-EventRelay-Key
  schemas:
    EventSubmission:
      type: object
      required:
        - event_type
        - payload
      properties:
        event_type:
          type: string
          example: payment.succeeded
        payload:
          type: object
          example: { "id": "evt_123", "amount": 1000 }
    IngestionReceipt:
      type: object
      properties:
        event_id:
          type: string
          format: uuid
        status:
          type: string
          example: QUEUED
```
---

## 3. Client SDK Generation

Because EventRelay is documented using OpenAPI, client libraries can be generated automatically using the **OpenAPI Generator CLI**:

```bash
# Generate Java client SDK
npx @openapitools/openapi-generator-cli generate \
  -i http://localhost:8080/v3/api-docs \
  -g java \
  -o ./sdks/java
```
- Client SDKs can be compiled and published to internal repository managers (such as Nexus or Artifactory) as part of the release pipeline.
