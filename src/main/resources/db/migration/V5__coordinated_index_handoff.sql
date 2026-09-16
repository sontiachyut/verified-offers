CREATE TABLE index_online_run (
    run_id uuid PRIMARY KEY REFERENCES index_rebuild,
    state text NOT NULL DEFAULT 'CAPTURED' CHECK (state IN ('CAPTURED','PAUSED','REPLAYING','BUILDING','SWITCHING','ACTIVE','ABORTED')),
    live_alias varchar(63) NOT NULL,
    old_index varchar(255) NOT NULL,
    endpoint text NOT NULL,
    kafka_start jsonb NOT NULL,
    kafka_end jsonb,
    candidate_id uuid REFERENCES index_rebuild,
    lease_token uuid,
    lease_until timestamptz,
    last_error text CHECK (last_error IN ('STEP_FAILED','WINDOW_INVALID','EVENT_INVALID','ALIAS_CHANGED','CANDIDATE_INVALID')),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (state NOT IN ('REPLAYING','BUILDING','SWITCHING','ACTIVE') OR kafka_end IS NOT NULL),
    CHECK (state NOT IN ('BUILDING','SWITCHING','ACTIVE') OR candidate_id IS NOT NULL),
    CHECK (state NOT IN ('ACTIVE','ABORTED') OR lease_token IS NULL)
);
CREATE TABLE index_route (
    alias varchar(63) PRIMARY KEY,
    blocked_by uuid REFERENCES index_online_run,
    protocol integer NOT NULL DEFAULT 1 CHECK (protocol=1)
);
CREATE TABLE index_online_cursor (
    run_id uuid NOT NULL REFERENCES index_online_run,
    partition_id integer NOT NULL CHECK (partition_id>=0),
    next_offset bigint NOT NULL CHECK (next_offset>=0),
    end_offset bigint NOT NULL CHECK (end_offset>=next_offset),
    PRIMARY KEY (run_id,partition_id)
);
CREATE TABLE index_online_expected (
    run_id uuid NOT NULL REFERENCES index_online_run,
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    version bigint NOT NULL,
    PRIMARY KEY (run_id,tenant_id,merchant_id,offer_id),
    FOREIGN KEY (tenant_id,merchant_id,offer_id,version) REFERENCES offer_version
);
