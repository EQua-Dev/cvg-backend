-- M2: onboarding links, player profiles with photos, and profiling questionnaire responses.

-- Private link a member opens to claim their account (set a passcode) and fill their profile.
CREATE TABLE onboarding_link (
    id          UUID PRIMARY KEY,
    member_id   UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_by  UUID        REFERENCES member (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_onboarding_member ON onboarding_link (member_id);

-- Uploaded images. Kept in Postgres for v1 (photos are resized to ~600px by the app first).
CREATE TABLE media_asset (
    id            UUID PRIMARY KEY,
    club_id       UUID        NOT NULL REFERENCES club (id),
    content_type  VARCHAR(40) NOT NULL,
    size_bytes    INTEGER     NOT NULL,
    data          BYTEA       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE member_profile (
    member_id          UUID PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    photo_id           UUID REFERENCES media_asset (id),
    favoured_position  VARCHAR(4),
    other_positions    JSONB       NOT NULL DEFAULT '[]',
    dominant_foot      VARCHAR(5),
    weak_foot          SMALLINT    CHECK (weak_foot BETWEEN 1 AND 5),
    strengths          JSONB       NOT NULL DEFAULT '[]',
    weaknesses         JSONB       NOT NULL DEFAULT '[]',
    height_cm          SMALLINT    CHECK (height_cm BETWEEN 120 AND 220),
    date_of_birth      DATE,
    state_of_origin    VARCHAR(40),
    preferred_jersey   SMALLINT    CHECK (preferred_jersey BETWEEN 1 AND 99),
    emergency_name     VARCHAR(120),
    emergency_phone    VARCHAR(20),
    consent_public     BOOLEAN,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0
);

-- One row per submission; the latest counts. Answers and computed result kept for re-scoring later.
CREATE TABLE profiling_response (
    id                     UUID PRIMARY KEY,
    member_id              UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    questionnaire_version  INTEGER     NOT NULL,
    position_group         VARCHAR(3)  NOT NULL,
    answers                JSONB       NOT NULL,
    result                 JSONB       NOT NULL,
    submitted_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_profiling_member ON profiling_response (member_id, submitted_at DESC);
