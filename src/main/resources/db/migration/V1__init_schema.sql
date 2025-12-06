-- Orders table
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    reserve_id VARCHAR(255) NOT NULL,
    side VARCHAR(4) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    quantity BIGINT NOT NULL,
    price BIGINT NOT NULL,
    time_in_force VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    filled_quantity BIGINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_account ON orders(account_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_orders_reserve_id ON orders(reserve_id);
CREATE INDEX idx_orders_account_status ON orders(account_id, status);
CREATE INDEX idx_orders_symbol_status ON orders(symbol, status);
CREATE INDEX idx_orders_cancel_lookup ON orders(order_id, account_id, status)
    WHERE status IN ('RECEIVED', 'ACCEPTED');

-- Order History table for audit trail
CREATE TABLE order_history (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    previous_status VARCHAR(20),
    quantity BIGINT NOT NULL,
    price BIGINT NOT NULL,
    filled_quantity BIGINT DEFAULT 0,
    reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_history_order_id ON order_history(order_id, created_at DESC);
CREATE INDEX idx_order_history_account_id ON order_history(account_id, created_at DESC);
CREATE INDEX idx_order_history_created ON order_history(created_at DESC);

-- Idempotency Keys table
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    order_id BIGINT,
    status VARCHAR(20) NOT NULL,
    response_payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idempotency_account ON idempotency_keys(account_id);
CREATE INDEX idx_idempotency_created ON idempotency_keys(created_at) WHERE status = 'PROCESSING';

ALTER TABLE idempotency_keys SET (fillfactor = 90);

-- Outbox table for transactional outbox pattern
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP
);

CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_created ON outbox(created_at);
