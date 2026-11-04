CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY,
  username text NOT NULL,
  username_normalized text NOT NULL UNIQUE,
  password_hash text,
  role text NOT NULL CHECK (role IN ('student','admin','system')),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled','deleted')),
  grade_id uuid,
  auth_version integer NOT NULL DEFAULT 1 CHECK (auth_version > 0),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  CHECK (role = 'system' OR password_hash IS NOT NULL)
);
CREATE TABLE IF NOT EXISTS grades (
  id uuid PRIMARY KEY, title text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'users'::regclass AND conname = 'users_grade_id_fkey') THEN
    ALTER TABLE users ADD CONSTRAINT users_grade_id_fkey FOREIGN KEY (grade_id) REFERENCES grades(id) ON DELETE RESTRICT;
  END IF;
END $$;
INSERT INTO users (id,username,username_normalized,role,created_by)
VALUES ('00000000-0000-4000-8000-000000000001','__system__','__system__','system','00000000-0000-4000-8000-000000000001') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS sessions (
  id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  auth_version integer NOT NULL, csrf_hash text NOT NULL, expires_at timestamptz NOT NULL, revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS sessions_owner_id_idx ON sessions(owner_id,id);
CREATE TABLE IF NOT EXISTS refresh_tokens (
  id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
  token_hash text NOT NULL UNIQUE, consumed_at timestamptz, expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS subjects (
  id uuid PRIMARY KEY, title text NOT NULL, status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS questions (
  id uuid PRIMARY KEY, subject_id uuid NOT NULL REFERENCES subjects(id) ON DELETE RESTRICT,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS question_versions (
  id uuid PRIMARY KEY, question_id uuid NOT NULL REFERENCES questions(id) ON DELETE RESTRICT,
  version integer NOT NULL CHECK (version > 0),
  content jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (question_id,version)
);
CREATE TABLE IF NOT EXISTS papers (
  id uuid PRIMARY KEY, title text NOT NULL,
  type text NOT NULL CHECK (type IN ('fixed','timed','task')),
  status text NOT NULL CHECK (status IN ('draft','published','archived')),
  duration_seconds integer NOT NULL CHECK (duration_seconds BETWEEN 1 AND 86400),
  start_at timestamptz, end_at timestamptz,
  current_version integer NOT NULL DEFAULT 1 CHECK (current_version > 0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  CHECK (type <> 'timed' OR (start_at IS NOT NULL AND end_at IS NOT NULL AND start_at < end_at))
);
CREATE INDEX IF NOT EXISTS papers_created_at_idx ON papers(created_at,id);
CREATE INDEX IF NOT EXISTS papers_title_idx ON papers(title,id);
CREATE TABLE IF NOT EXISTS paper_versions (
  id uuid PRIMARY KEY, paper_id uuid NOT NULL REFERENCES papers(id) ON DELETE RESTRICT,
  version integer NOT NULL CHECK (version > 0), snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (paper_id,version), UNIQUE (id,paper_id)
);
CREATE TABLE IF NOT EXISTS paper_items (
  id uuid PRIMARY KEY, paper_version_id uuid NOT NULL REFERENCES paper_versions(id) ON DELETE RESTRICT,
  question_version_id uuid NOT NULL REFERENCES question_versions(id) ON DELETE RESTRICT,
  position integer NOT NULL CHECK (position > 0), max_score numeric(8,2) NOT NULL CHECK (max_score >= 0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (paper_version_id,position), UNIQUE (paper_version_id,id)
);
CREATE TABLE IF NOT EXISTS tasks (
  id uuid PRIMARY KEY, paper_id uuid NOT NULL REFERENCES papers(id) ON DELETE RESTRICT,
  paper_version_id uuid NOT NULL, grade_id uuid NOT NULL REFERENCES grades(id) ON DELETE RESTRICT,
  status text NOT NULL CHECK (status IN ('draft','published','archived')),
  start_at timestamptz NOT NULL, end_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  CHECK (start_at < end_at),
  FOREIGN KEY (paper_version_id,paper_id) REFERENCES paper_versions(id,paper_id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS task_recipients (
  id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (task_id,owner_id)
);
CREATE TABLE IF NOT EXISTS attempts (
  id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  paper_version_id uuid NOT NULL REFERENCES paper_versions(id) ON DELETE RESTRICT,
  task_id uuid REFERENCES tasks(id) ON DELETE RESTRICT,
  status text NOT NULL DEFAULT 'running' CHECK (status IN ('running','submitted','completed')),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0), snapshot jsonb NOT NULL,
  started_at timestamptz NOT NULL, deadline timestamptz NOT NULL, submitted_at timestamptz,
  total numeric(8,2) CHECK (total >= 0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (task_id,owner_id), UNIQUE (id,paper_version_id),
  CHECK (deadline > started_at),
  CHECK ((status = 'running' AND submitted_at IS NULL AND total IS NULL) OR
    (status = 'submitted' AND submitted_at IS NOT NULL AND total IS NULL) OR
    (status = 'completed' AND submitted_at IS NOT NULL AND total IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS attempts_owner_created_at_idx ON attempts(owner_id,created_at,id);
CREATE INDEX IF NOT EXISTS attempts_deadline_idx ON attempts(deadline,id) WHERE status = 'running';
CREATE TABLE IF NOT EXISTS attempt_answers (
  id uuid PRIMARY KEY, attempt_id uuid NOT NULL, paper_version_id uuid NOT NULL, item_id uuid NOT NULL,
  answer text NOT NULL CHECK (length(answer) <= 10000),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (attempt_id,item_id),
  FOREIGN KEY (attempt_id,paper_version_id) REFERENCES attempts(id,paper_version_id) ON DELETE RESTRICT,
  FOREIGN KEY (paper_version_id,item_id) REFERENCES paper_items(paper_version_id,id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS gradings (
  id uuid PRIMARY KEY, attempt_id uuid NOT NULL UNIQUE REFERENCES attempts(id) ON DELETE RESTRICT,
  scores jsonb NOT NULL, total numeric(8,2) NOT NULL CHECK (total >= 0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS mistakes (
  id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  question_id uuid NOT NULL REFERENCES questions(id) ON DELETE RESTRICT,
  last_attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE RESTRICT, snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (owner_id,question_id)
);
CREATE INDEX IF NOT EXISTS mistakes_owner_created_at_idx ON mistakes(owner_id,created_at,id);
CREATE TABLE IF NOT EXISTS idempotency_records (
  id uuid PRIMARY KEY, scope text NOT NULL, key text NOT NULL, digest text NOT NULL, result jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (scope,key)
);
CREATE TABLE IF NOT EXISTS outbox_events (
  id uuid PRIMARY KEY, action text NOT NULL, object_id uuid NOT NULL, payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','leased','completed','failed')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, lease_until timestamptz, lease_token uuid, last_error text,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  UNIQUE (action,object_id)
);
CREATE TABLE IF NOT EXISTS event_receipts (
  id uuid PRIMARY KEY, event_id uuid NOT NULL UNIQUE REFERENCES outbox_events(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS audit_events (
  id uuid PRIMARY KEY, action text NOT NULL, object_id uuid NOT NULL, request_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'audit events are append only' USING ERRCODE = '42501'; END $$;
DROP TRIGGER IF EXISTS audit_append_only ON audit_events;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
