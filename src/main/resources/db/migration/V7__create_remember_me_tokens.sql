CREATE TABLE exchange.persistent_logins (
    username VARCHAR(50) NOT NULL REFERENCES exchange.users(username) ON DELETE CASCADE,
    series VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    last_used TIMESTAMPTZ NOT NULL
);
CREATE INDEX persistent_logins_username ON exchange.persistent_logins(username);
CREATE INDEX persistent_logins_expiry ON exchange.persistent_logins(last_used);
