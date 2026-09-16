CREATE TABLE feed_job (
    job_id uuid PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    idempotency_key varchar(64) NOT NULL,
    source_label varchar(64) NOT NULL,
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    byte_count integer NOT NULL CHECK (byte_count BETWEEN 1 AND 1048576),
    total integer NOT NULL CHECK (total BETWEEN 1 AND 1000),
    processed integer NOT NULL DEFAULT 0,
    applied integer NOT NULL DEFAULT 0 CHECK (applied>=0),
    replayed integer NOT NULL DEFAULT 0 CHECK (replayed>=0),
    rejected integer NOT NULL DEFAULT 0 CHECK (rejected>=0),
    cancelled integer NOT NULL DEFAULT 0 CHECK (cancelled>=0),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','PAUSED','CANCELLED')),
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 5),
    last_error text CHECK (last_error='DATABASE_ERROR'),
    available_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE (tenant_id,merchant_id,idempotency_key),
    UNIQUE (job_id,tenant_id,merchant_id),
    CHECK (processed=applied+replayed+rejected+cancelled AND processed BETWEEN 0 AND total),
    CHECK ((lease_token IS NULL)=(lease_until IS NULL)),
    CHECK ((state='RUNNING')=(lease_token IS NOT NULL)),
    CHECK (state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED') OR processed=total),
    CHECK (state<>'COMPLETED' OR (rejected=0 AND cancelled=0)),
    CHECK (state<>'COMPLETED_WITH_ERRORS' OR rejected>0)
);
CREATE INDEX feed_ready ON feed_job(available_at,created_at,job_id) WHERE state IN ('QUEUED','RUNNING');
CREATE TABLE feed_row (
    job_id uuid NOT NULL,
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    row_number integer NOT NULL CHECK (row_number BETWEEN 1 AND 1000),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    payload jsonb,
    offer_id varchar(64),
    version bigint,
    source_updated_at timestamptz,
    state text NOT NULL CHECK (state IN ('PENDING','APPLIED','REPLAYED','REJECTED','CANCELLED')),
    reason text CHECK (reason IN ('INVALID_JSON','INVALID_OFFER','SCOPE_MISMATCH','FUTURE_SOURCE','VERSION_CONFLICT','CANCELLED')),
    receipt_offer_id varchar(64),
    receipt_version bigint,
    finished_at timestamptz,
    PRIMARY KEY (job_id,row_number),
    FOREIGN KEY (job_id,tenant_id,merchant_id) REFERENCES feed_job(job_id,tenant_id,merchant_id),
    FOREIGN KEY (tenant_id,merchant_id,receipt_offer_id,receipt_version) REFERENCES offer_version,
    CHECK ((receipt_offer_id IS NULL)=(receipt_version IS NULL)),
    CHECK ((state IN ('APPLIED','REPLAYED'))=(receipt_version IS NOT NULL)),
    CHECK (receipt_version IS NULL OR (receipt_version=version AND receipt_offer_id=offer_id)),
    CHECK ((payload IS NULL)=(offer_id IS NULL) AND (offer_id IS NULL)=(version IS NULL) AND (version IS NULL)=(source_updated_at IS NULL)),
    CHECK (state NOT IN ('PENDING','APPLIED','REPLAYED') OR payload IS NOT NULL),
    CHECK ((state='PENDING')=(finished_at IS NULL)),
    CHECK ((state IN ('REJECTED','CANCELLED'))=(reason IS NOT NULL))
);
CREATE INDEX feed_pending ON feed_row(job_id,row_number) WHERE state='PENDING';
CREATE TABLE feed_action (
    action_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES feed_job,
    action text NOT NULL CHECK (action IN ('RETRY','CANCEL')),
    reason varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE FUNCTION protect_feed_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'feed provenance is immutable'; END IF;
    IF (NEW.job_id,NEW.tenant_id,NEW.merchant_id,NEW.idempotency_key,NEW.source_label,NEW.sha256,NEW.byte_count,NEW.total,NEW.created_at)
       IS DISTINCT FROM (OLD.job_id,OLD.tenant_id,OLD.merchant_id,OLD.idempotency_key,OLD.source_label,OLD.sha256,OLD.byte_count,OLD.total,OLD.created_at)
       OR OLD.state IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED') THEN
        RAISE EXCEPTION 'feed provenance or terminal result is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_feed_job BEFORE UPDATE OR DELETE ON feed_job FOR EACH ROW EXECUTE FUNCTION protect_feed_job();
CREATE FUNCTION protect_feed_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'feed row is immutable'; END IF;
    IF OLD.state<>'PENDING' OR
       (NEW.job_id,NEW.tenant_id,NEW.merchant_id,NEW.row_number,NEW.sha256,NEW.payload,NEW.offer_id,NEW.version,NEW.source_updated_at)
       IS DISTINCT FROM (OLD.job_id,OLD.tenant_id,OLD.merchant_id,OLD.row_number,OLD.sha256,OLD.payload,OLD.offer_id,OLD.version,OLD.source_updated_at) THEN
        RAISE EXCEPTION 'feed input or receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_feed_row BEFORE UPDATE OR DELETE ON feed_row FOR EACH ROW EXECUTE FUNCTION protect_feed_row();
CREATE TRIGGER immutable_feed_action BEFORE UPDATE OR DELETE ON feed_action FOR EACH ROW EXECUTE FUNCTION deny_history_change();
