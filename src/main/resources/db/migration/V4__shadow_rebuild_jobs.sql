CREATE TABLE index_rebuild (
    job_id uuid PRIMARY KEY,
    state text NOT NULL DEFAULT 'BUILDING'
        CHECK (state IN ('BUILDING','VALIDATING','SNAPSHOT_VALIDATED','INVALID')),
    snapshot_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    total bigint NOT NULL DEFAULT 0 CHECK (total BETWEEN 0 AND 100000),
    projected bigint NOT NULL DEFAULT 0 CHECK (projected BETWEEN 0 AND total),
    validated bigint NOT NULL DEFAULT 0 CHECK (validated BETWEEN 0 AND projected),
    lease_token uuid,
    lease_until timestamptz,
    last_error text CHECK (last_error IN ('STEP_FAILED','OWNERSHIP_MISMATCH','CONTENT_MISMATCH','COUNT_MISMATCH')),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (state <> 'BUILDING' OR validated = 0),
    CHECK (state <> 'VALIDATING' OR projected = total),
    CHECK (state <> 'SNAPSHOT_VALIDATED' OR (projected = total AND validated = total)),
    CHECK (state NOT IN ('SNAPSHOT_VALIDATED','INVALID') OR lease_token IS NULL)
);

CREATE TABLE index_rebuild_item (
    job_id uuid NOT NULL REFERENCES index_rebuild,
    ordinal bigint NOT NULL CHECK (ordinal BETWEEN 1 AND 100001),
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    version bigint NOT NULL,
    PRIMARY KEY (job_id,ordinal),
    UNIQUE (job_id,tenant_id,merchant_id,offer_id),
    FOREIGN KEY (tenant_id,merchant_id,offer_id,version) REFERENCES offer_version
);
CREATE TRIGGER immutable_rebuild_snapshot BEFORE UPDATE OR DELETE ON index_rebuild_item
FOR EACH ROW EXECUTE FUNCTION deny_history_change();
