-- V1__initial_schema.sql
-- Payment Processing Engine - Initial Schema

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(64)    NOT NULL UNIQUE,   -- Caller-provided dedup key
    merchant_id     VARCHAR(50)    NOT NULL,
    customer_id     VARCHAR(50)    NOT NULL,
    amount          DECIMAL(15, 2) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'INR',
    payment_method  VARCHAR(20)    NOT NULL,          -- UPI, CARD, NETBANKING, WALLET
    status          VARCHAR(20)    NOT NULL DEFAULT 'INITIATED',
    failure_reason  VARCHAR(500),
    retry_count     INT            NOT NULL DEFAULT 0,
    metadata        JSONB,                            -- Flexible extra fields (UPI VPA, card last4, etc.)
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    settled_at      TIMESTAMP
);

CREATE TABLE settlement_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID           NOT NULL REFERENCES payments(id),
    settlement_ref  VARCHAR(100)   NOT NULL UNIQUE,   -- Bank/PSP reference number
    settled_amount  DECIMAL(15, 2) NOT NULL,
    settlement_date DATE           NOT NULL,
    bank_response   JSONB,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE TABLE reconciliation_reports (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_date           DATE           NOT NULL UNIQUE,
    total_initiated       INT            NOT NULL DEFAULT 0,
    total_settled         INT            NOT NULL DEFAULT 0,
    total_failed          INT            NOT NULL DEFAULT 0,
    total_pending         INT            NOT NULL DEFAULT 0,
    discrepancy_count     INT            NOT NULL DEFAULT 0,
    total_amount_initiated DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total_amount_settled  DECIMAL(15, 2) NOT NULL DEFAULT 0,
    status                VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED',
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Outbox table for transactional outbox pattern
-- Ensures event is published only after DB commit (no dual-write problem)
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(50)    NOT NULL,          -- payment id
    event_type      VARCHAR(50)    NOT NULL,          -- PAYMENT_INITIATED, PAYMENT_SETTLED etc.
    payload         JSONB          NOT NULL,
    published       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP
);

-- Indexes for query performance
CREATE INDEX idx_payments_merchant_id     ON payments(merchant_id);
CREATE INDEX idx_payments_customer_id     ON payments(customer_id);
CREATE INDEX idx_payments_status          ON payments(status);
CREATE INDEX idx_payments_created_at      ON payments(created_at);
CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);
CREATE INDEX idx_outbox_unpublished       ON outbox_events(published) WHERE published = FALSE;
CREATE INDEX idx_settlement_payment_id    ON settlement_records(payment_id);

-- Auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
