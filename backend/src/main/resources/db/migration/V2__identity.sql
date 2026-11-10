ALTER TABLE grades ADD COLUMN enabled boolean NOT NULL DEFAULT true;
GRANT SELECT ON grades TO exam_app;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(32) NOT NULL,
    username_normalized varchar(32) NOT NULL UNIQUE,
    password_hash varchar(60),
    role varchar(8) NOT NULL CHECK (role IN ('STUDENT', 'ADMIN')),
    status varchar(8) NOT NULL CHECK (status IN ('ENABLED', 'DISABLED', 'DELETED')),
    grade_id uuid REFERENCES grades(id),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status = 'DELETED' OR password_hash IS NOT NULL),
    CHECK (status = 'DELETED' OR ((role = 'STUDENT') = (grade_id IS NOT NULL)))
);

CREATE TABLE web_sessions (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX web_sessions_user_id_idx ON web_sessions (user_id);

CREATE TABLE identity_guard (
    id integer PRIMARY KEY CHECK (id = 1)
);
INSERT INTO identity_guard VALUES (1);

CREATE TABLE login_limits (
    bucket varchar(72) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    failures integer NOT NULL CHECK (failures >= 0)
);

GRANT SELECT, INSERT, UPDATE ON users TO exam_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON web_sessions, login_limits TO exam_app;
GRANT SELECT, UPDATE ON identity_guard TO exam_app;
