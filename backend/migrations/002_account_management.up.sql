-- Additive migration: existing accounts and old application reads remain valid.
-- A fresh installation applies 001 and 002 in one transaction. Flush the system
-- actor's deferred self-reference before indexing users, then restore its mode.
SET CONSTRAINTS users_created_by_fkey IMMEDIATE;
CREATE TABLE IF NOT EXISTS account_guards (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
INSERT INTO account_guards(id,created_by) VALUES
  ('00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001') ON CONFLICT DO NOTHING;
CREATE INDEX IF NOT EXISTS users_directory_idx ON users(role,status,created_at,id);
CREATE INDEX IF NOT EXISTS users_grade_id_idx ON users(grade_id,created_at,id);
CREATE INDEX IF NOT EXISTS users_username_idx ON users(username_normalized,id);
SET CONSTRAINTS users_created_by_fkey DEFERRED;
