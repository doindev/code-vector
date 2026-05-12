CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    email VARCHAR(255),
    created_at TIMESTAMP
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    action VARCHAR(64),
    ts TIMESTAMP
);

SELECT id, email FROM users WHERE id = 1;
INSERT INTO audit_log (user_id, action) VALUES (1, 'login');
