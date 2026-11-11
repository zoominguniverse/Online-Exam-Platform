ALTER TABLE attempts
    ADD COLUMN frozen_at timestamptz,
    ADD COLUMN freeze_reason varchar(10) CHECK (freeze_reason IN ('MANUAL', 'DEADLINE')),
    ADD COLUMN automatic_score_minor integer CHECK (automatic_score_minor >= 0),
    ADD CONSTRAINT attempts_freeze_consistency CHECK (
        (status = 'IN_PROGRESS' AND frozen_at IS NULL AND freeze_reason IS NULL AND automatic_score_minor IS NULL)
        OR (status <> 'IN_PROGRESS' AND frozen_at IS NOT NULL AND freeze_reason IS NOT NULL
            AND automatic_score_minor IS NOT NULL)
    );
CREATE INDEX attempts_due_idx ON attempts(deadline_at, id) WHERE status = 'IN_PROGRESS';

CREATE TABLE attempt_results (
    attempt_id uuid NOT NULL,
    question_id uuid NOT NULL,
    score_minor integer CHECK (score_minor >= 0),
    PRIMARY KEY (attempt_id, question_id),
    FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_items(attempt_id, question_id)
);

CREATE TABLE attempt_submit_keys (
    owner_id uuid NOT NULL REFERENCES users(id),
    key varchar(128) NOT NULL,
    attempt_id uuid NOT NULL REFERENCES attempts(id),
    request_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, key)
);

CREATE TABLE mistakes (
    owner_id uuid NOT NULL REFERENCES users(id),
    question_id uuid NOT NULL REFERENCES questions(id),
    attempt_id uuid NOT NULL,
    question_version integer NOT NULL,
    last_error_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, question_id),
    FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_items(attempt_id, question_id),
    FOREIGN KEY (question_id, question_version) REFERENCES question_versions(question_id, version)
);
GRANT SELECT, INSERT ON attempt_results TO exam_app;
GRANT SELECT, INSERT, DELETE ON attempt_submit_keys TO exam_app;
GRANT SELECT, INSERT, UPDATE ON mistakes TO exam_app;
