CREATE TABLE attempts (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    paper_id uuid NOT NULL REFERENCES papers(id),
    paper_version_id uuid NOT NULL,
    task_id uuid REFERENCES tasks(id),
    status varchar(20) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'WAIT_SELF_GRADE', 'COMPLETED')),
    started_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    final_score_minor integer,
    FOREIGN KEY (paper_id, paper_version_id) REFERENCES paper_versions(paper_id, id),
    CHECK (deadline_at > started_at)
);
CREATE INDEX attempts_owner_idx ON attempts(owner_id, started_at DESC, id);
CREATE UNIQUE INDEX attempts_task_paper_once_idx ON attempts(owner_id, paper_id) WHERE task_id IS NOT NULL;

CREATE TABLE attempt_items (
    attempt_id uuid NOT NULL REFERENCES attempts(id),
    position integer NOT NULL,
    group_position integer NOT NULL,
    group_name varchar(80) NOT NULL,
    question_id uuid NOT NULL,
    question_version integer NOT NULL,
    score_minor integer NOT NULL CHECK (score_minor > 0),
    type varchar(12) NOT NULL,
    stem_html text NOT NULL,
    options jsonb NOT NULL,
    correct_answer jsonb NOT NULL,
    explanation_html text NOT NULL,
    PRIMARY KEY (attempt_id, position),
    UNIQUE (attempt_id, question_id),
    FOREIGN KEY (question_id, question_version) REFERENCES question_versions(question_id, version)
);

CREATE TABLE attempt_start_keys (
    owner_id uuid NOT NULL REFERENCES users(id),
    key varchar(128) NOT NULL,
    paper_id uuid NOT NULL REFERENCES papers(id),
    attempt_id uuid NOT NULL REFERENCES attempts(id),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, key)
);

GRANT SELECT, INSERT, UPDATE ON attempts, attempt_items, attempt_start_keys TO exam_app;
GRANT DELETE ON attempt_start_keys TO exam_app;
