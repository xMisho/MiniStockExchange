CREATE TABLE exchange.stocks (
    symbol VARCHAR(20) PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    reference_price NUMERIC(19, 4) NOT NULL,
    CONSTRAINT stocks_symbol_not_blank CHECK (length(btrim(symbol)) > 0),
    CONSTRAINT stocks_company_name_not_blank CHECK (length(btrim(company_name)) > 0),
    CONSTRAINT stocks_reference_price_positive CHECK (
        reference_price > 0 AND reference_price <> 'NaN'::NUMERIC
    )
);
