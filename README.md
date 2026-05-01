# Payment Gateway — Distributed Microservices Study

A containerised, event-driven microservices system that simulates a simplified payment gateway. The goal is to study **Nginx load balancing**, **service decoupling via RabbitMQ**, and **bounded-context database isolation**, all orchestrated with Docker Compose.

---

## Architecture Overview

```
                                    Client
                                      │
                                      ▼
        ┌─────────────────────────────────────────────────────────┐
        │  Nginx  (port 80, least_conn load balancer)             │
        │  • Generates X-Request-ID for every request             │
        │  • Routes /payments  →  payment_pool                    │
        │  • Routes /invoices  →  invoice_pool                    │
        └───────────────┬──────────────────────────┬──────────────┘
                        │                          │
           ┌────────────▼────────────┐  ┌──────────▼────────────┐
           │  payment-api-1          │  │  invoice-api-1        │
           │  payment-api-2          │  │  invoice-api-2        │
           └──────┬──────────┬───────┘  └───────────────────┬───┘
                  │          │                   ▲          │
               persist    publish             consume    persist
                  │       event                event        │
                  │          │                   │          │
           ┌──────▼──────┐   │                   │  ┌───────▼───────┐
           │ payment-db  │   │                   │  │  invoice-db   │
           │ PostgreSQL  │   │                   │  │  PostgreSQL   │
           └─────────────┘   │                   │  └───────────────┘
                             │                   │
                             ▼                   │
                          ┌──────────────────────────┐
                          │        RabbitMQ          │
                          │  payments.exchange       │
                          │  payment.processed       │
                          │  .queue                  │
                          │  payment.processed.dlq   │
                          │  (dead-letter)           │
                          └──────────────────────────┘
```

Every inbound request is assigned a 32-char hex `X-Request-ID` by Nginx. That ID travels through HTTP headers, RabbitMQ message payloads, database rows, and every structured log line — making a single request traceable end-to-end across all services and instances.

---

## Services

| Service | Image / Build | Instances | Exposed |
|---|---|---|---|
| `nginx` | nginx:1.25-alpine | 1 | `localhost:80` |
| `payment-api` | Spring Boot 3.2 / Java 21 | 2 | internal only |
| `invoice-api` | Spring Boot 3.2 / Java 21 | 2 | internal only |
| `rabbitmq` | rabbitmq:3.12-management-alpine | 1 | `localhost:15672` (management UI) |
| `payment-db` | postgres:16-alpine | 1 | internal only |
| `invoice-db` | postgres:16-alpine | 1 | internal only |
| `prometheus` | prom/prometheus:v2.48.0 | 1 | `localhost:9090` |
| `loki` | grafana/loki:2.9.3 | 1 | internal only |
| `promtail` | grafana/promtail:2.9.3 | 1 | internal only |
| `grafana` | grafana/grafana:10.2.3 | 1 | `localhost:3000` |

All services share the `app-net` Docker bridge network. Only Nginx and the RabbitMQ management UI are reachable from the host.

---

## Running Locally

**Prerequisites:** Docker and Docker Compose v2.

```bash
# 1. Copy the environment file
cp .env.example .env

# 2. Start everything (first run will build the Spring Boot images)
docker compose up --build

# 3. Wait until all healthchecks pass (~2 minutes on first build)
docker compose ps
```

Services start in dependency order via `depends_on: condition: service_healthy`. Nginx waits for all four application instances to be healthy before accepting traffic.

---

## API Reference

All requests go through Nginx at `http://localhost`.

### payment-api — `POST /payments`

```bash
curl -s -X POST http://localhost/payments \
  -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","amount":150.00,"currency":"BRL","method":"CREDIT_CARD"}'
```

**Request body**

```json
{
  "customerId": "uuid",
  "amount": 150.00,
  "currency": "BRL",
  "method": "CREDIT_CARD"
}
```

**Response body**

```json
{
  "requestId":  "a3f1c8...",
  "paymentId":  "uuid",
  "status":     "APPROVED",
  "amount":     150.00,
  "currency":   "BRL",
  "method":     "CREDIT_CARD",
  "customerId": "uuid",
  "createdAt":  "2025-01-01T12:00:00Z"
}
```

The `X-Request-ID` header is returned in the response. Replaying the same request with the same `X-Request-ID` returns the original response without creating a duplicate — idempotent by design.

### payment-api — `GET /payments`

```bash
curl http://localhost/payments
```

### payment-api — `GET /payments/{id}`

```bash
curl http://localhost/payments/<paymentId>
```

### invoice-api — `GET /invoices`

```bash
curl http://localhost/invoices
```

### invoice-api — `GET /invoices/{id}`

```bash
curl http://localhost/invoices/<invoiceId>
```

### invoice-api — `GET /invoices/payment/{paymentId}`

```bash
curl http://localhost/invoices/payment/<paymentId>
```

---

## Key Concepts Demonstrated

### Load balancing

Nginx uses `least_conn` across two instances of each service with HTTP/1.1 keepalives (`keepalive 32`). The `upstream` field in every Nginx access log entry shows which backend handled each request, so you can observe traffic distribution in real time:

```bash
docker compose logs nginx | grep '"uri":"/payments"' | grep -o '"upstream":"[^"]*"'
```

### Event-driven decoupling

`payment-api` and `invoice-api` share no database and make no direct HTTP calls to each other. When a payment is created, `payment-api` publishes a `PaymentProcessedEvent` to RabbitMQ. `invoice-api` consumes that event and generates the invoice record independently.

**RabbitMQ topology:**

| Component | Name |
|---|---|
| Exchange | `payments.exchange` (topic, durable) |
| Queue | `payment.processed.queue` (durable) |
| Routing key | `payment.processed` |
| Dead-letter exchange | `payments.dlx` |
| Dead-letter queue | `payment.processed.dlq` |

Failed messages (after retries) are routed to the DLQ instead of being lost. Check activity at `http://localhost:15672` (user/password from `.env`).

### Idempotency

Both services guard against duplicate processing:

- `payments` table has a `UNIQUE` constraint on `request_id`
- `invoices` table has a `UNIQUE` constraint on `request_id` and `payment_id`
- Before processing, each service checks for an existing record with the same `requestId`
- Race conditions between instances are caught via `DataIntegrityViolationException` — the existing record is returned instead

### Structured logging

All application logs are emitted as JSON to stdout using `logstash-logback-encoder`. Every line carries:

```json
{
  "timestamp": "2025-01-01T12:00:00.000Z",
  "level":     "INFO",
  "service":   "payment-api",
  "instance":  "payment-api-1",
  "requestId": "a3f1c8...",
  "message":   "Payment created successfully"
}
```

The `requestId` is injected into the SLF4J MDC via a `OncePerRequestFilter` on every HTTP request, and at the start of every RabbitMQ consumer call, so it appears on every log line automatically — including those from framework code.

Nginx access logs are also JSON-formatted so the full request path is observable in a single log stream.

---

## Project Structure

```
.
├── docker-compose.yml
├── .env.example
├── nginx/
│   └── nginx.conf
├── prometheus/
│   └── prometheus.yml
├── loki/
│   └── loki-config.yml
├── promtail/
│   └── promtail-config.yml
├── grafana/
│   └── provisioning/
│       └── datasources/
│           └── datasources.yml
├── payment-api/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/study/payment/
│       ├── config/RabbitMQConfig.java      # exchange, queue, binding declarations
│       ├── controller/PaymentController.java
│       ├── service/PaymentService.java     # persist + publish
│       ├── filter/RequestIdFilter.java     # MDC injection
│       └── ...
└── invoice-api/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/study/invoice/
        ├── config/RabbitMQConfig.java
        ├── controller/InvoiceController.java
        ├── consumer/PaymentEventConsumer.java  # RabbitMQ listener, manual ACK
        ├── service/InvoiceService.java
        ├── filter/RequestIdFilter.java
        └── ...
```

Database schemas are managed by Flyway migrations (`src/main/resources/db/migration/`).

---

## Environment Variables

Configured in `.env` (copy from `.env.example`):

| Variable | Used by |
|---|---|
| `PAYMENT_DB_USER` / `PAYMENT_DB_PASSWORD` | payment-db, payment-api |
| `INVOICE_DB_USER` / `INVOICE_DB_PASSWORD` | invoice-db, invoice-api |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | rabbitmq, payment-api, invoice-api |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | grafana |

Each application container also receives `INSTANCE_ID` (e.g. `payment-api-1`) from Compose, which is embedded in every log line to identify which replica produced it.

---

## Observability Stack

```
METRICS PIPELINE
  payment-api-1/2 :8080/actuator/prometheus ─┐
  invoice-api-1/2 :8080/actuator/prometheus ─┴──► Prometheus ──► Grafana
                                               (scrapes every 15s) (PromQL)

LOGS PIPELINE
  All container stdout (JSON) ──► Promtail ──► Loki ──► Grafana
                                  (Docker socket  (stores    (LogQL)
                                   discovery)      by label)
```

- **Prometheus** scrapes `/actuator/prometheus` from all four application instances every 15 seconds. Every metric is tagged with `service` and `instance` labels so you can filter by individual replica.
- **Promtail** tails all container logs via the Docker socket, attaches `service` and `container` labels from the Compose metadata, and ships the raw log lines to Loki.
- **Loki** stores logs indexed only by those labels. The full JSON content is searchable at query time using LogQL — no schema needed upfront.
- **Grafana** connects to both as datasources (auto-provisioned on startup) and is the single UI for exploring logs and metrics.

### Accessing Grafana

Open `http://localhost:3000` in your browser. Log in with the credentials from your `.env` file (`GRAFANA_USER` / `GRAFANA_PASSWORD`, default `admin` / `admin`).

Both datasources (Prometheus and Loki) are provisioned automatically — no manual setup required.

### Searching logs

Go to **Explore** (compass icon in the left sidebar) and select the **Loki** datasource.

**Show all logs from a service:**
```logql
{service="payment-api"}
```

**Filter to a specific instance:**
```logql
{service="payment-api", container="payment-api-1"}
```

**Parse JSON and filter by log level:**
```logql
{service="payment-api"} | json | level="ERROR"
```

**Trace a single request end-to-end across all services:**
```logql
{compose_project="loadbalancer"} | json | requestId="a3f1c8..."
```

This last query is the most powerful one: given a `requestId` from an HTTP response header, it returns every log line produced by Nginx, `payment-api`, and `invoice-api` in the exact order they were emitted — across whichever instances handled the request.

**Show all logs from all services, newest first:**
```logql
{compose_project="loadbalancer"} | json
```

### Viewing metrics

In **Explore**, switch to the **Prometheus** datasource.

**Request rate per instance (last 5 min):**
```promql
rate(http_server_requests_seconds_count{service="payment-api"}[5m])
```

**Check all instances are up:**
```promql
up
```

**JVM heap usage by instance:**
```promql
jvm_memory_used_bytes{area="heap", service="payment-api"}
```

You can also open `http://localhost:9090` to use the Prometheus UI directly and inspect scrape targets under **Status → Targets**.
