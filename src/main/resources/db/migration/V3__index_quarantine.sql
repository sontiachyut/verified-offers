CREATE TABLE index_quarantine (
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL CHECK (partition_id >= 0),
    record_offset bigint NOT NULL CHECK (record_offset >= 0),
    reason text NOT NULL CHECK (reason = 'INVALID_ENVELOPE'),
    payload_sha256 varchar(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    quarantined_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);
