CREATE TABLE wechat_bindings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES users(id),
    app_id varchar(64) NOT NULL,
    open_id varchar(128) NOT NULL,
    UNIQUE (app_id, open_id)
);

CREATE TABLE wechat_tokens (
    token_hash char(64) PRIMARY KEY,
    binding_id uuid NOT NULL REFERENCES wechat_bindings(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX wechat_tokens_binding_idx ON wechat_tokens (binding_id);

CREATE TABLE wechat_used_codes (
    code_hash char(64) PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX wechat_used_codes_expiry_idx ON wechat_used_codes (expires_at);

GRANT SELECT, INSERT, DELETE ON wechat_bindings, wechat_tokens, wechat_used_codes TO exam_app;
