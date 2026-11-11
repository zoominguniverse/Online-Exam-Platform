CREATE TABLE gradings (
    attempt_id uuid PRIMARY KEY REFERENCES attempts(id),
    owner_id uuid NOT NULL REFERENCES users(id),
    source varchar(8) NOT NULL CHECK (source = 'SELF'),
    confirmed_at timestamptz NOT NULL,
    final_score_minor integer NOT NULL CHECK (final_score_minor >= 0)
);
CREATE TABLE operation_keys (
    actor_id uuid NOT NULL REFERENCES users(id),
    operation varchar(32) NOT NULL,
    key varchar(128) NOT NULL,
    target_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, operation, key)
);
CREATE TABLE profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    name varchar(100) NOT NULL DEFAULT '',
    phone varchar(32) NOT NULL DEFAULT '',
    birthday date,
    avatar_id uuid REFERENCES attachments(id),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0)
);
CREATE TABLE messages (
    id uuid PRIMARY KEY,
    sender_id uuid NOT NULL REFERENCES users(id),
    title varchar(200) NOT NULL,
    body text NOT NULL,
    sent_at timestamptz NOT NULL
);
CREATE TABLE message_recipients (
    message_id uuid NOT NULL REFERENCES messages(id),
    user_id uuid NOT NULL REFERENCES users(id),
    read_at timestamptz,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX message_recipients_user_idx ON message_recipients(user_id, message_id);
CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    actor_id uuid REFERENCES users(id),
    module varchar(80) NOT NULL,
    action varchar(80) NOT NULL,
    target_id uuid,
    result varchar(40) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX audit_actor_time_idx ON audit_events(actor_id, created_at DESC, id);
CREATE INDEX audit_request_idx ON audit_events(request_id);
CREATE TABLE user_daily_activity (
    user_id uuid NOT NULL REFERENCES users(id),
    day date NOT NULL,
    PRIMARY KEY (user_id, day)
);
CREATE TABLE deleted_account_ledger (
    user_id uuid PRIMARY KEY,
    deleted_at timestamptz NOT NULL
);
GRANT SELECT, INSERT ON gradings, operation_keys, messages, audit_events, user_daily_activity, deleted_account_ledger TO exam_app;
GRANT DELETE ON operation_keys TO exam_app;
GRANT SELECT, INSERT, UPDATE ON profiles, message_recipients TO exam_app;
GRANT UPDATE ON attempt_results TO exam_app;
