CREATE TABLE payments (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  VARCHAR(64)   NOT NULL,
    customer_id UUID          NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    method      VARCHAR(32)   NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payments_request_id UNIQUE (request_id)
);

CREATE INDEX idx_payments_request_id ON payments (request_id);
CREATE INDEX idx_payments_status     ON payments (status);
CREATE INDEX idx_payments_created_at ON payments (created_at DESC);
