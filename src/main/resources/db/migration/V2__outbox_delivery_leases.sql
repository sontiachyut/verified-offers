ALTER TABLE outbox
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN quarantined_at timestamptz,
    ADD COLUMN last_error text CHECK (last_error IN ('SEND_FAILED', 'SEND_INTERRUPTED', 'ATTEMPTS_EXHAUSTED')),
    ADD CONSTRAINT outbox_lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    ADD CONSTRAINT outbox_terminal_state CHECK (
        (published_at IS NULL OR (quarantined_at IS NULL AND lease_token IS NULL))
        AND (quarantined_at IS NULL OR lease_token IS NULL));

CREATE INDEX outbox_delivery_due ON outbox(next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL AND quarantined_at IS NULL;

CREATE TABLE outbox_replay (
    replay_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES outbox(event_id),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    previous_attempts integer NOT NULL CHECK (previous_attempts BETWEEN 0 AND 8),
    reason varchar(300) NOT NULL CHECK (length(trim(reason)) > 0)
);
CREATE INDEX outbox_replay_event ON outbox_replay(event_id, requested_at);
