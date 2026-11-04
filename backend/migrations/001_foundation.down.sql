-- Empty/disposable databases only. This removes all foundation data; it cannot restore discarded records.
DROP TABLE IF EXISTS audit_events,event_receipts,outbox_events,idempotency_records,mistakes,gradings,attempt_answers,attempts,task_recipients,tasks,paper_items,paper_versions,papers,question_versions,questions,subjects,refresh_tokens,sessions;
ALTER TABLE IF EXISTS users DROP CONSTRAINT IF EXISTS users_grade_id_fkey;
DROP TABLE IF EXISTS grades;
DROP TABLE IF EXISTS users;
DROP FUNCTION IF EXISTS reject_audit_mutation();
