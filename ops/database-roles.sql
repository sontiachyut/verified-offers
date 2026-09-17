-- Run as the migration owner in the dedicated application database, after migrations.
-- No login/password is created; provision credentials separately. Re-runnable.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='offers_runtime') THEN
        CREATE ROLE offers_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='offers_operator') THEN
        CREATE ROLE offers_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('offers_runtime','offers_operator')
               AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolcanlogin)) THEN
        RAISE EXCEPTION 'Expected unprivileged NOLOGIN application groups; refusing to adopt existing privileged roles';
    END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
-- Dedicated database only: reconcile these two application groups, not unrelated roles.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM offers_runtime,offers_operator;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM offers_runtime,offers_operator;
REVOKE CREATE ON SCHEMA public FROM offers_runtime,offers_operator;
GRANT USAGE ON SCHEMA public TO offers_runtime,offers_operator;
GRANT SELECT,INSERT,UPDATE ON offer_key,offer_head,outbox,feed_job,feed_row TO offers_runtime;
GRANT SELECT,INSERT ON offer_version,feed_action,outbox_replay,index_quarantine,index_route TO offers_runtime;
GRANT SELECT ON index_rebuild,index_rebuild_item,index_online_run,index_online_cursor,index_online_expected,index_reconciliation TO offers_runtime;
GRANT USAGE,SELECT ON SEQUENCE feed_action_action_id_seq,outbox_replay_replay_id_seq TO offers_runtime;
GRANT offers_runtime TO offers_operator;
GRANT SELECT,INSERT,UPDATE ON index_rebuild,index_online_run,index_online_cursor,index_online_expected,index_route,index_reconciliation TO offers_operator;
GRANT SELECT,INSERT ON index_rebuild_item TO offers_operator;
-- SELECT FOR UPDATE needs UPDATE privilege; the immutable trigger still forbids changes.
GRANT UPDATE ON index_quarantine TO offers_operator;
-- No DELETE, TRUNCATE, schema ownership, migration-table access or default future grants.
