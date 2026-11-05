CREATE TABLE exchange.order_requests (
    user_id BIGINT NOT NULL REFERENCES exchange.users(id) ON DELETE CASCADE,
    request_key VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL REFERENCES exchange.orders(id) ON DELETE CASCADE,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key)
);
CREATE TABLE exchange.paper_allocations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES exchange.users(id) ON DELETE CASCADE,
    stock_symbol VARCHAR(20) NOT NULL REFERENCES exchange.stocks(symbol),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price NUMERIC(19,4) NOT NULL CHECK (price > 0 AND price <> 'NaN'::NUMERIC),
    operator_id BIGINT REFERENCES exchange.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX paper_allocations_owner ON exchange.paper_allocations(user_id, id);
