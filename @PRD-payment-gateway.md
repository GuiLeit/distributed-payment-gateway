# PRD — Payment Gateway Distributed Architecture (Study Project)

## 1. Overview

A containerised, event-driven microservices system that simulates a simplified payment gateway. The goal is to study **Nginx load balancing**, **service-to-service decoupling via RabbitMQ**, and **bounded-context database isolation**, all orchestrated with Docker Compose.

A second phase (out of scope here) will add observability with Prometheus, Loki + Promtail, and Grafana. Every logging and metrics decision in this phase must leave the door open for that integration.

---

## 2. Goals

- Run the full system with a single `docker compose up --build`
- Two instances of each application service, both reachable only through Nginx
- Each service owns exactly one database — no cross-service DB access, ever
- A payment request arriving at Nginx must be traceable end-to-end by a single `requestId`
- Application code must be simple: persist a record, publish or consume an event — no complex business rules
- Log output must be structured in a way that Prometheus + Loki + Grafana can consume without schema changes later

---

## 3. Services

| Service | Technology | Instances | Port (internal) |
|---|---|---|---|
| nginx | nginx:1.25-alpine | 1 | 80 (exposed to host) |
| payment-api | Spring Boot 3.2 / Java 21 | 2 | 8080 |
| invoice-api | Spring Boot 3.2 / Java 21 | 2 | 8080 |
| rabbitmq | rabbitmq:3.12-management-alpine | 1 | 5672 / 15672 |
| payment-db | postgres:16-alpine | 1 | 5432 |
| invoice-db | postgres:16-alpine | 1 | 5432 |

Only Nginx (port 80) and RabbitMQ management UI (port 15672) are exposed to the host. All other services communicate on an internal Docker bridge network (`app-net`).

---

## 4. Docker Compose Requirements

- File: `docker-compose.yml` at the project root
- Network: single bridge network `app-net` shared by all services
- Services must start in dependency order using `depends_on` with `condition: service_healthy`
- Every service must define a `healthcheck`
- DB init SQL files are mounted at `/docker-entrypoint-initdb.d/`
- Each application service receives its configuration through environment variables (no secrets hardcoded in source), defined inside `.env` file and must be equal to `.env.example`
- Named volumes for both databases to persist data between restarts

### Environment variables each app service must accept

```
SERVER_PORT          # default 8080
INSTANCE_ID          # e.g. payment-api-1 — injected by compose, used in logs
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
SPRING_RABBITMQ_HOST
```

---

## 5. Nginx

### Path-based routing

```
location /payments  →  upstream payment_pool  (payment-api-1:8080, payment-api-2:8080)
location /invoices  →  upstream invoice_pool   (invoice-api-1:8080, invoice-api-2:8080)
```

Both upstreams use `least_conn` load balancing and HTTP keepalives (`keepalive 32`).

### Request ID

Nginx generates `$request_id` (built-in 32-char hex variable) for every inbound request and:
- Forwards it to the upstream as the `X-Request-ID` header
- Returns it to the client as the `X-Request-ID` response header (`add_header ... always`)

### Additional headers forwarded upstream

```
X-Request-ID
X-Forwarded-For
X-Real-IP
X-Forwarded-Host
X-Forwarded-Proto
Host
```

### Access log format

Nginx access logs must be JSON-structured so Promtail can scrape them later:

```nginx
log_format json_combined escape=json
  '{'
    '"time":"$time_iso8601",'
    '"request_id":"$request_id",'
    '"method":"$request_method",'
    '"uri":"$request_uri",'
    '"status":$status,'
    '"upstream":"$upstream_addr",'
    '"request_time":$request_time,'
    '"bytes_sent":$bytes_sent,'
    '"remote_addr":"$remote_addr"'
  '}';
```

### Additional endpoints

`GET /nginx-health` — returns `{"status":"ok"}`, access log off, used by Docker healthcheck.

---

## 6. RabbitMQ

### Topology

| Component | Name | Type |
|---|---|---|
| Exchange | `payments.exchange` | Topic, durable |
| Queue | `payment.processed.queue` | Durable |
| Routing key (publish) | `payment.processed` | — |
| Binding | `payment.processed.queue` → `payments.exchange` | key: `payment.processed` |
| Dead-letter exchange | `payments.dlx` | Direct, durable |
| Dead-letter queue | `payment.processed.dlq` | Durable |

The `payment.processed.queue` must be declared with `x-dead-letter-exchange: payments.dlx` so failed messages (after max retries) are routed to the DLQ instead of being lost.

### Message format (`PaymentProcessedEvent`)

```json
{
  "requestId":   "a3f1...",
  "paymentId":   "uuid",
  "status":      "APPROVED",
  "amount":      150.00,
  "currency":    "BRL",
  "customerId":  "uuid",
  "createdAt":   "2025-01-01T12:00:00Z"
}
```

### Consumer behaviour

- `invoice-api` uses **manual ACK** (`acknowledge-mode: manual`)
- On success: `basicAck`
- On failure: `basicNack` with `requeue=true` (up to a retry limit you can set in application config)
- After max retries the message is dead-lettered automatically by RabbitMQ

---

## 7. payment-api

### Responsibility

Receives payment HTTP requests from Nginx, validates them, persists a payment record, publishes a `payment.processed` event to RabbitMQ.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/payments` | Create a payment |
| `GET` | `/payments` | List all payments (pagination optional) |
| `GET` | `/payments/{id}` | Get a payment by ID |

### POST /payments — request body

```json
{
  "customerId": "uuid",
  "amount":     150.00,
  "currency":   "BRL",
  "method":     "CREDIT_CARD"
}
```

### POST /payments — response body

```json
{
  "requestId":  "a3f1...",
  "paymentId":  "uuid",
  "status":     "APPROVED",
  "amount":     150.00,
  "currency":   "BRL",
  "method":     "CREDIT_CARD",
  "customerId": "uuid",
  "createdAt":  "2025-01-01T12:00:00Z"
}
```

### Payment status lifecycle

```
PENDING  →  APPROVED  (simulate: always approve, or random 80/20)
         →  REJECTED
```

Status transitions happen in memory during the same request — no async processing inside `payment-api`. Persist the final status, then publish the event.

### Idempotency

- The `payments` table has a `UNIQUE` constraint on `request_id`
- Before processing, check if a payment with the incoming `X-Request-ID` already exists
- If found: return the existing payment response immediately, skip all processing
- If the DB `UNIQUE` constraint is violated (race condition between two instances): catch `DataIntegrityViolationException`, fetch and return the existing record
- Only publish the RabbitMQ event if this is the first time the payment is being processed (not a duplicate)

### Reading X-Request-ID in Spring

```java
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> create(
    @RequestHeader("X-Request-ID") String requestId,
    @RequestBody PaymentRequest request) { ... }
```

---

## 8. invoice-api

### Responsibility

Exposes HTTP read endpoints for invoices AND consumes `payment.processed` events from RabbitMQ to generate invoice records. Both roles run in the same Spring Boot process.

### HTTP Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/invoices` | List all invoices |
| `GET` | `/invoices/{id}` | Get invoice by invoice ID |
| `GET` | `/invoices/payment/{paymentId}` | Get invoice by payment ID |

### Event consumer

Listens on `payment.processed.queue`. On receiving an event:

1. Check idempotency: if an invoice with `requestId` already exists, `basicAck` and return
2. Only process events with `status = APPROVED` — rejected payments should not generate invoices (ack and discard)
3. Generate and persist an `Invoice` record
4. `basicAck` on success, `basicNack` (requeue) on failure

### Idempotency

- The `invoices` table has a `UNIQUE` constraint on `request_id`
- Same catch-`DataIntegrityViolationException` pattern as `payment-api`

---

## 9. Database Schemas

All database schemas must be handled by flyway migrations.

### payment-db

```sql
-- Main domain table
CREATE TABLE payments (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  VARCHAR(64)  NOT NULL,
    customer_id UUID         NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    method      VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payments_request_id UNIQUE (request_id)
);

CREATE INDEX idx_payments_request_id      ON payments (request_id);
CREATE INDEX idx_payments_status          ON payments (status);
CREATE INDEX idx_payments_created_at      ON payments (created_at DESC);
```

### invoice-db

```sql
-- Main domain table
CREATE TABLE invoices (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  VARCHAR(64)  NOT NULL,
    payment_id  UUID         NOT NULL,
    customer_id UUID         NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ISSUED',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_invoices_request_id  UNIQUE (request_id),
    CONSTRAINT uq_invoices_payment_id  UNIQUE (payment_id)
);

CREATE INDEX idx_invoices_request_id         ON invoices (request_id);
CREATE INDEX idx_invoices_payment_id         ON invoices (payment_id);
CREATE INDEX idx_invoices_created_at         ON invoices (created_at DESC);
```

---

## 10. Logging Strategy (Phase 1 — structured for Phase 2)

All application logs must be **structured JSON** on stdout so Promtail can scrape container logs without a parser. Use `logstash-logback-encoder` in both services.

### Required fields in every log line

```json
{
  "timestamp":    "2025-01-01T12:00:00.000Z",
  "level":        "INFO",
  "service":      "payment-api",
  "instance":     "payment-api-1",
  "requestId":    "a3f1...",
  "logger":       "com.study.payment.service.PaymentService",
  "message":      "Payment created successfully",
  "thread":       "http-nio-8080-exec-1"
}
```

`requestId` must be injected into the SLF4J MDC at the start of every HTTP request (use a `HandlerInterceptor` or `OncePerRequestFilter`) and at the start of every RabbitMQ consumer call. This way every log line in the container output carries the `requestId` automatically.

### logback-spring.xml (both services)

Configure a single `ConsoleAppender` with `LogstashEncoder`. No file appenders needed — Docker captures stdout, Promtail will tail it.

### Dependency to add (both pom.xml)

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

---

## 11. Metrics Readiness (for Phase 2)

Both services must expose the Spring Boot Actuator + Micrometer Prometheus endpoint so Phase 2 can scrape without code changes.

### Dependencies to add (both pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### application.yml (both services)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  metrics:
    tags:
      service: ${spring.application.name}
      instance: ${INSTANCE_ID}
```

This tags every metric with `service` and `instance` labels so Grafana dashboards can filter by instance and observe the load balancing in action.

---

## 12. Project Structure

```
payment-gateway-study/
├── docker-compose.yml
├── nginx/
│   └── nginx.conf
├── init-db/
│   ├── payment-init.sql
│   └── invoice-init.sql
├── payment-api/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/study/payment/
│       │   ├── PaymentApplication.java
│       │   ├── config/
│       │   │   └── RabbitMQConfig.java
│       │   ├── controller/
│       │   │   └── PaymentController.java
│       │   ├── dto/
│       │   │   ├── PaymentRequest.java
│       │   │   ├── PaymentResponse.java
│       │   │   └── PaymentProcessedEvent.java
│       │   ├── model/
│       │   │   └── Payment.java
│       │   ├── repository/
│       │   │   └── PaymentRepository.java
│       │   ├── service/
│       │   │   └── PaymentService.java
│       │   └── filter/
│       │       └── RequestIdFilter.java       ← injects requestId into MDC
│       └── resources/
│           ├── application.yml
│           └── logback-spring.xml
└── invoice-api/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/
        ├── java/com/study/invoice/
        │   ├── InvoiceApplication.java
        │   ├── config/
        │   │   └── RabbitMQConfig.java
        │   ├── controller/
        │   │   └── InvoiceController.java
        │   ├── consumer/
        │   │   └── PaymentEventConsumer.java  ← RabbitMQ listener
        │   ├── dto/
        │   │   ├── InvoiceResponse.java
        │   │   └── PaymentProcessedEvent.java
        │   ├── model/
        │   │   └── Invoice.java
        │   ├── repository/
        │   │   └── InvoiceRepository.java
        │   ├── service/
        │   │   └── InvoiceService.java
        │   └── filter/
        │       └── RequestIdFilter.java
        └── resources/
            ├── application.yml
            └── logback-spring.xml
```

---

## 13. Dockerfile (same template for both services)

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 14. Out of Scope (Phase 2)

The following must not be implemented now but the codebase must not block them:

- Prometheus scrape config (`prometheus.yml`)
- Loki + Promtail setup (`promtail-config.yml` tailing Docker container logs)
- Grafana dashboards and datasources
- Distributed tracing (OpenTelemetry / Zipkin)
- Authentication / API keys on Nginx
- TLS termination at Nginx

---

## 15. Acceptance Criteria

- `docker compose up --build` starts all services with no manual intervention
- `POST http://localhost/payments` returns a payment response with `X-Request-ID` in the response headers
- `GET http://localhost/payments` returns the list of payments
- After a successful POST, the `invoice-api` logs show the event was consumed and an invoice was created
- `GET http://localhost/invoices/payment/{paymentId}` returns the generated invoice
- Sending the same request twice with the same `X-Request-ID` header returns the same payment without creating a duplicate
- Both instances of each service appear in the Nginx upstream logs (`upstream` field)
- RabbitMQ management UI (`http://localhost:15672`) shows the `payment.processed.queue` with activity
- All application log lines on stdout are valid JSON containing `requestId`, `service`, and `instance` fields