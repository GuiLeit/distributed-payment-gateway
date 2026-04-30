CREATE TABLE invoices (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  VARCHAR(64)   NOT NULL,
    payment_id  UUID          NOT NULL,
    customer_id UUID          NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'ISSUED',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_invoices_request_id UNIQUE (request_id),
    CONSTRAINT uq_invoices_payment_id UNIQUE (payment_id)
);

CREATE INDEX idx_invoices_request_id ON invoices (request_id);
CREATE INDEX idx_invoices_payment_id ON invoices (payment_id);
CREATE INDEX idx_invoices_created_at ON invoices (created_at DESC);
