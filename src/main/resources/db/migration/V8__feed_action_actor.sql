ALTER TABLE feed_action
    ADD COLUMN actor_subject varchar(200) NOT NULL DEFAULT 'local-operator' CHECK (length(actor_subject)>0),
    ADD COLUMN authenticated boolean NOT NULL DEFAULT false;
