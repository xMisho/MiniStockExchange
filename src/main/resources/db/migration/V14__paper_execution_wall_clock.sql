-- CURRENT_TIMESTAMP is fixed at transaction start. A provider read can happen later.
-- Execution/audit timestamps must describe the actual write, after the observed quote.
ALTER TABLE exchange.external_paper_orders ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE exchange.external_paper_orders ALTER COLUMN updated_at SET DEFAULT clock_timestamp();
ALTER TABLE exchange.external_paper_fills ALTER COLUMN executed_at SET DEFAULT clock_timestamp();
