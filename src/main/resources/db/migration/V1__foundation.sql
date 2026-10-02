-- M1 Foundation: clubs, members, roles, seasons, auth, audit.
-- Every club-owned table carries club_id so the platform can host more clubs later.

CREATE TABLE club (
    id          UUID PRIMARY KEY,
    slug        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    city        VARCHAR(80),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id              UUID PRIMARY KEY,
    club_id         UUID         NOT NULL REFERENCES club (id),
    member_no       INTEGER      NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    nickname        VARCHAR(40),
    phone           VARCHAR(20)  NOT NULL,
    jersey_number   SMALLINT     CHECK (jersey_number BETWEEN 1 AND 99),
    status          VARCHAR(16)  NOT NULL,
    joined_on       DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_member_no    UNIQUE (club_id, member_no),
    CONSTRAINT uq_member_phone UNIQUE (club_id, phone)
);

CREATE TABLE member_role (
    member_id   UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    role        VARCHAR(16) NOT NULL,
    PRIMARY KEY (member_id, role)
);

CREATE TABLE season (
    id          UUID PRIMARY KEY,
    club_id     UUID        NOT NULL REFERENCES club (id),
    name        VARCHAR(60) NOT NULL,
    starts_on   DATE        NOT NULL,
    ends_on     DATE        NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_season_dates CHECK (ends_on > starts_on)
);
-- At most one active season per club.
CREATE UNIQUE INDEX uq_season_active ON season (club_id) WHERE active;

CREATE TABLE otp_challenge (
    id           UUID PRIMARY KEY,
    phone        VARCHAR(20) NOT NULL,
    code_hash    VARCHAR(64) NOT NULL,
    attempts     SMALLINT    NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_phone_created ON otp_challenge (phone, created_at DESC);

CREATE TABLE auth_session (
    id            UUID PRIMARY KEY,
    member_id     UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    last_seen_at  TIMESTAMPTZ,
    user_agent    VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_session_member ON auth_session (member_id);

CREATE TABLE audit_event (
    id           UUID PRIMARY KEY,
    club_id      UUID        NOT NULL REFERENCES club (id),
    actor_id     UUID        REFERENCES member (id),
    action       VARCHAR(60) NOT NULL,
    entity_type  VARCHAR(40) NOT NULL,
    entity_id    UUID,
    summary      VARCHAR(255),
    before_state JSONB,
    after_state  JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_club_created ON audit_event (club_id, created_at DESC);
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id);

-- The founding club. Single-club for now; the id is referenced in configuration.
INSERT INTO club (id, slug, name, city)
VALUES ('00000000-0000-0000-0000-00000000c0c0', 'cvg', 'CVG FC', 'Abuja');
