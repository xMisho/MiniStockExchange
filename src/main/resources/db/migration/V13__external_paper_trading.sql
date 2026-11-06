-- External-price paper accounts are independent of the existing matching sandbox.
CREATE TABLE exchange.external_paper_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES exchange.users(id),
    cash NUMERIC(24,4) NOT NULL DEFAULT 100000 CHECK (cash >= 0)
);
CREATE TABLE exchange.external_paper_positions (
    user_id BIGINT NOT NULL REFERENCES exchange.external_paper_accounts(user_id),
    symbol VARCHAR(15) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    average_price NUMERIC(24,12) NOT NULL CHECK (average_price >= 0),
    realized_pnl NUMERIC(24,4) NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id,symbol)
);
CREATE TABLE exchange.external_paper_orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES exchange.external_paper_accounts(user_id),
    symbol VARCHAR(15) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type VARCHAR(6) NOT NULL CHECK (order_type IN ('MARKET','LIMIT')),
    original_quantity INTEGER NOT NULL CHECK (original_quantity BETWEEN 1 AND 1000000),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    limit_price NUMERIC(19,4),
    status VARCHAR(9) NOT NULL CHECK (status IN ('OPEN','FILLED','CANCELLED')),
    request_key VARCHAR(128) NOT NULL,
    request_fingerprint TEXT NOT NULL,
    submission_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id,request_key),
    CHECK ((order_type='LIMIT' AND limit_price>0) OR (order_type='MARKET' AND limit_price IS NULL))
);
CREATE INDEX external_paper_orders_user ON exchange.external_paper_orders(user_id,id DESC);
CREATE INDEX external_paper_orders_pending ON exchange.external_paper_orders(id) WHERE status='OPEN';
CREATE TABLE exchange.external_paper_fills (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES exchange.external_paper_orders(id),
    price NUMERIC(19,4) NOT NULL CHECK (price>0),
    quantity INTEGER NOT NULL CHECK (quantity>0),
    quote_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    feed VARCHAR(10) NOT NULL DEFAULT 'IEX'
);
