-- Removes only the new guard and indexes. User rows, versions and audit history are preserved.
DROP INDEX IF EXISTS users_directory_idx;
DROP INDEX IF EXISTS users_grade_id_idx;
DROP INDEX IF EXISTS users_username_idx;
DROP TABLE IF EXISTS account_guards;
