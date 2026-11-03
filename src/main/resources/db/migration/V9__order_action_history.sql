CREATE TABLE exchange.order_actions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES exchange.orders(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('SUBMITTED', 'AMENDED', 'CANCELLED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    previous_price NUMERIC(19,4),
    price NUMERIC(19,4),
    previous_remaining INTEGER,
    requested_quantity INTEGER NOT NULL,
    remaining_quantity INTEGER NOT NULL,
    filled_quantity INTEGER NOT NULL
);
CREATE INDEX order_actions_order ON exchange.order_actions(order_id, id);
