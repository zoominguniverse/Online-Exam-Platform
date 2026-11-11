-- Keep the deletion ledger and profile privacy correct even during rollback to an older application.
INSERT INTO deleted_account_ledger(user_id,deleted_at)
SELECT id,updated_at FROM users WHERE status='DELETED' ON CONFLICT DO NOTHING;
CREATE FUNCTION retain_account_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.deleted_account_ledger(user_id,deleted_at)
    VALUES (NEW.id,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
    UPDATE public.profiles SET name='',phone='',birthday=NULL,avatar_id=NULL,version=version+1
    WHERE user_id=NEW.id;
    RETURN NEW;
END
$$;
CREATE TRIGGER retain_account_deletion AFTER UPDATE OF status ON users
FOR EACH ROW WHEN (NEW.status='DELETED' AND OLD.status IS DISTINCT FROM NEW.status)
EXECUTE FUNCTION retain_account_deletion();
