-- M3: collections (what members owe), and an append-only ledger of every naira in and out.
-- Amounts are in kobo (₦1 = 100 kobo) so there is no floating-point rounding.

CREATE TABLE collection (
    id              UUID PRIMARY KEY,
    club_id         UUID         NOT NULL REFERENCES club (id),
    season_id       UUID         REFERENCES season (id),
    title           VARCHAR(80)  NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    amount_kobo     BIGINT       NOT NULL CHECK (amount_kobo > 0),
    due_date        DATE         NOT NULL,
    audience        VARCHAR(24)  NOT NULL,
    -- Monthly dues: collections in one series share series_id; period is 'YYYY-MM'.
    recurring       BOOLEAN      NOT NULL DEFAULT FALSE,
    series_id       UUID,
    period          VARCHAR(7),
    closed_at       TIMESTAMPTZ,
    created_by      UUID         REFERENCES member (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_collection_series_period UNIQUE (series_id, period)
);
CREATE INDEX ix_collection_club ON collection (club_id, due_date DESC);

-- Who is expected to pay a collection.
CREATE TABLE collection_member (
    collection_id  UUID NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    member_id      UUID NOT NULL REFERENCES member (id),
    PRIMARY KEY (collection_id, member_id)
);
CREATE INDEX ix_collection_member_member ON collection_member (member_id);

CREATE TABLE ledger_entry (
    id              UUID PRIMARY KEY,
    club_id         UUID         NOT NULL REFERENCES club (id),
    direction       VARCHAR(3)   NOT NULL CHECK (direction IN ('IN', 'OUT')),
    category        VARCHAR(20)  NOT NULL,
    amount_kobo     BIGINT       NOT NULL CHECK (amount_kobo > 0),
    member_id       UUID         REFERENCES member (id),
    collection_id   UUID         REFERENCES collection (id),
    method          VARCHAR(10)  NOT NULL,
    occurred_on     DATE         NOT NULL,
    note            VARCHAR(200),
    -- A correction points at the entry it cancels. Each entry can be reversed once.
    reverses_id     UUID         UNIQUE REFERENCES ledger_entry (id),
    recorded_by     UUID         REFERENCES member (id),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_club_date ON ledger_entry (club_id, occurred_on DESC, recorded_at DESC);
CREATE INDEX ix_ledger_collection ON ledger_entry (collection_id);
CREATE INDEX ix_ledger_member ON ledger_entry (member_id);

-- Receipts live beside the entry so the entry itself never changes.
CREATE TABLE ledger_receipt (
    entry_id   UUID PRIMARY KEY REFERENCES ledger_entry (id),
    media_id   UUID        NOT NULL REFERENCES media_asset (id),
    added_by   UUID        REFERENCES member (id),
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The ledger is append-only: the database itself refuses edits and deletes.
CREATE FUNCTION ledger_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger entries cannot be changed or deleted; record a reversal instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_no_update BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
CREATE TRIGGER ledger_receipt_no_update BEFORE UPDATE OR DELETE ON ledger_receipt
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
