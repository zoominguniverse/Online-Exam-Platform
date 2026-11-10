CREATE TABLE tasks (
    id uuid PRIMARY KEY,
    grade_id uuid NOT NULL REFERENCES grades(id),
    name varchar(100) NOT NULL,
    selected_papers uuid[] NOT NULL,
    state varchar(9) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz
);
CREATE INDEX tasks_grade_idx ON tasks(grade_id, state);

CREATE TABLE task_papers (
    task_id uuid NOT NULL REFERENCES tasks(id),
    paper_id uuid NOT NULL UNIQUE REFERENCES papers(id),
    paper_version_id uuid NOT NULL,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 200),
    PRIMARY KEY (task_id, paper_id),
    UNIQUE (task_id, position),
    FOREIGN KEY (paper_id, paper_version_id) REFERENCES paper_versions(paper_id, id)
);

CREATE TABLE task_recipients (
    task_id uuid NOT NULL REFERENCES tasks(id),
    user_id uuid NOT NULL REFERENCES users(id),
    PRIMARY KEY (task_id, user_id)
);
CREATE INDEX task_recipients_user_idx ON task_recipients(user_id, task_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON tasks TO exam_app;
GRANT SELECT, INSERT ON task_papers, task_recipients TO exam_app;
