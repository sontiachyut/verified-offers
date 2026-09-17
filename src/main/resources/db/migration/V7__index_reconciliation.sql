CREATE TABLE index_reconciliation (
    reconciliation_id uuid PRIMARY KEY,
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    version bigint NOT NULL,
    operator_label varchar(64) NOT NULL,
    reason varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    completed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    UNIQUE (topic,partition_id,record_offset),
    FOREIGN KEY (topic,partition_id,record_offset) REFERENCES index_quarantine,
    FOREIGN KEY (tenant_id,merchant_id,offer_id,version) REFERENCES offer_version
);
CREATE FUNCTION protect_index_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'reconciliation evidence is immutable'; END IF;
    IF OLD.completed_at IS NOT NULL OR
       (NEW.reconciliation_id,NEW.topic,NEW.partition_id,NEW.record_offset,NEW.tenant_id,
        NEW.merchant_id,NEW.offer_id,NEW.version,NEW.operator_label,NEW.reason,NEW.created_at)
       IS DISTINCT FROM
       (OLD.reconciliation_id,OLD.topic,OLD.partition_id,OLD.record_offset,OLD.tenant_id,
        OLD.merchant_id,OLD.offer_id,OLD.version,OLD.operator_label,OLD.reason,OLD.created_at)
       OR NEW.attempts<OLD.attempts THEN
        RAISE EXCEPTION 'reconciliation evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_index_reconciliation BEFORE UPDATE OR DELETE ON index_reconciliation
FOR EACH ROW EXECUTE FUNCTION protect_index_reconciliation();
CREATE TRIGGER immutable_index_quarantine BEFORE UPDATE OR DELETE ON index_quarantine
FOR EACH ROW EXECUTE FUNCTION deny_history_change();
