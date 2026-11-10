CREATE TABLE attachments (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    storage_key uuid NOT NULL UNIQUE,
    media_type varchar(16) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg', 'image/webp')),
    byte_size integer NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
    sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX attachments_created_at_idx ON attachments(created_at);

CREATE TABLE attachment_links (
    question_id uuid NOT NULL,
    question_version integer NOT NULL,
    part varchar(12) NOT NULL CHECK (part IN ('STEM', 'EXPLANATION')),
    attachment_id uuid NOT NULL REFERENCES attachments(id) ON DELETE RESTRICT,
    FOREIGN KEY (question_id, question_version) REFERENCES question_versions(question_id, version),
    PRIMARY KEY (question_id, question_version, part, attachment_id)
);
CREATE INDEX attachment_links_attachment_idx ON attachment_links(attachment_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON attachments TO exam_app;
GRANT SELECT, INSERT ON attachment_links TO exam_app;
