ALTER TABLE exchange.users
    ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT users_role_valid CHECK (role IN ('USER', 'ADMIN'));
