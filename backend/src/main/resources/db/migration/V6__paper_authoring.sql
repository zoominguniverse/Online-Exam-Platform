CREATE TABLE papers (
    id uuid PRIMARY KEY,
    subject_id uuid NOT NULL REFERENCES subjects(id) ON DELETE RESTRICT,
    type varchar(8) NOT NULL CHECK (type IN ('FIXED', 'TIMED', 'TASK')),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    draft_id uuid,
    published_id uuid,
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX papers_subject_idx ON papers(subject_id);

CREATE TABLE paper_versions (
    id uuid PRIMARY KEY,
    paper_id uuid NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision >= 1),
    name varchar(100) NOT NULL,
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 180),
    opens_at timestamptz,
    closes_at timestamptz,
    published_at timestamptz,
    UNIQUE (paper_id, revision),
    UNIQUE (paper_id, id)
);
ALTER TABLE papers ADD CONSTRAINT papers_draft_fk FOREIGN KEY (id, draft_id)
    REFERENCES paper_versions (paper_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE papers ADD CONSTRAINT papers_published_fk FOREIGN KEY (id, published_id)
    REFERENCES paper_versions (paper_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE paper_items (
    paper_version_id uuid NOT NULL REFERENCES paper_versions(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 200),
    group_position integer NOT NULL CHECK (group_position BETWEEN 1 AND 200),
    group_name varchar(80) NOT NULL,
    question_id uuid NOT NULL,
    question_version integer NOT NULL,
    score_minor integer NOT NULL CHECK (score_minor > 0),
    PRIMARY KEY (paper_version_id, position),
    UNIQUE (paper_version_id, question_id),
    FOREIGN KEY (question_id, question_version) REFERENCES question_versions(question_id, version)
);
CREATE INDEX paper_items_question_idx ON paper_items(question_id, question_version);

GRANT SELECT, INSERT, UPDATE, DELETE ON papers, paper_versions, paper_items TO exam_app;
