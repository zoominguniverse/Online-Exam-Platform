ALTER TABLE task_papers ADD CONSTRAINT task_papers_frozen_version_unique
    UNIQUE (task_id, paper_id, paper_version_id);
ALTER TABLE attempts ADD CONSTRAINT attempts_frozen_task_paper_fk
    FOREIGN KEY (task_id, paper_id, paper_version_id)
    REFERENCES task_papers(task_id, paper_id, paper_version_id);
ALTER TABLE attempts ADD CONSTRAINT attempts_frozen_recipient_fk
    FOREIGN KEY (task_id, owner_id) REFERENCES task_recipients(task_id, user_id);
