CREATE TABLE attempt_answers (
    attempt_id uuid NOT NULL REFERENCES attempts(id),
    question_id uuid NOT NULL,
    answer jsonb NOT NULL,
    PRIMARY KEY (attempt_id, question_id),
    FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_items(attempt_id, question_id)
);
GRANT SELECT, INSERT, DELETE ON attempt_answers TO exam_app;
