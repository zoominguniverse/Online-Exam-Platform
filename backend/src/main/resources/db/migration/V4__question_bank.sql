CREATE TABLE subjects (
    id uuid PRIMARY KEY,
    grade_id uuid NOT NULL REFERENCES grades(id),
    name varchar(80) NOT NULL,
    name_normalized varchar(160) NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (grade_id, name_normalized)
);

CREATE TABLE questions (
    id uuid PRIMARY KEY,
    subject_id uuid NOT NULL REFERENCES subjects(id) ON DELETE RESTRICT,
    current_version integer NOT NULL CHECK (current_version >= 1),
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX questions_subject_idx ON questions(subject_id);

CREATE TABLE question_versions (
    question_id uuid NOT NULL REFERENCES questions(id),
    version integer NOT NULL CHECK (version >= 1),
    subject_id uuid NOT NULL REFERENCES subjects(id) ON DELETE RESTRICT,
    type varchar(12) NOT NULL CHECK (type IN ('SINGLE', 'MULTIPLE', 'TRUE_FALSE', 'GAP', 'SHORT')),
    difficulty smallint NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    score_minor integer NOT NULL CHECK (score_minor > 0),
    stem_html text NOT NULL,
    options jsonb NOT NULL,
    answer jsonb NOT NULL,
    explanation_html text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (question_id, version)
);
CREATE INDEX question_versions_subject_idx ON question_versions(subject_id);
ALTER TABLE questions ADD CONSTRAINT questions_current_version_fk
    FOREIGN KEY (id, current_version) REFERENCES question_versions(question_id, version)
    DEFERRABLE INITIALLY DEFERRED;

GRANT SELECT, INSERT, UPDATE, DELETE ON subjects TO exam_app;
GRANT SELECT, INSERT, UPDATE ON questions TO exam_app;
GRANT SELECT, INSERT ON question_versions TO exam_app;
