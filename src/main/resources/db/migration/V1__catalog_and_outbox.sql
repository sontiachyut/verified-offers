CREATE TABLE offer_key (
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    PRIMARY KEY (tenant_id, merchant_id, offer_id)
);

CREATE TABLE offer_version (
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    price_minor bigint NOT NULL CHECK (price_minor >= 0),
    currency char(3) NOT NULL CHECK (currency = 'USD'),
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    source_updated_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted boolean NOT NULL,
    payload jsonb NOT NULL,
    payload_hash char(64) NOT NULL,
    PRIMARY KEY (tenant_id, merchant_id, offer_id, version),
    FOREIGN KEY (tenant_id, merchant_id, offer_id) REFERENCES offer_key
);

CREATE TABLE offer_head (
    tenant_id varchar(64) NOT NULL,
    merchant_id varchar(64) NOT NULL,
    offer_id varchar(64) NOT NULL,
    version bigint NOT NULL,
    PRIMARY KEY (tenant_id, merchant_id, offer_id),
    FOREIGN KEY (tenant_id, merchant_id, offer_id, version) REFERENCES offer_version
);

CREATE FUNCTION deny_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'offer history is immutable';
END;
$$;
CREATE TRIGGER immutable_offer_history BEFORE UPDATE OR DELETE ON offer_version
FOR EACH ROW EXECUTE FUNCTION deny_history_change();

CREATE TABLE outbox (
    event_id uuid PRIMARY KEY,
    event_type text NOT NULL,
    tenant_id varchar(64) NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    UNIQUE (tenant_id, aggregate_id, aggregate_version)
);
CREATE INDEX outbox_unpublished ON outbox(created_at, event_id) WHERE published_at IS NULL;
