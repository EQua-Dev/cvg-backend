-- M4: weekly training schedule, sessions, availability ("I'm in" by default) and attendance.

-- A recurring slot, e.g. Wednesdays 17:30 at Area 1, compulsory. Times are Abuja local.
CREATE TABLE training_pattern (
    id          UUID PRIMARY KEY,
    club_id     UUID         NOT NULL REFERENCES club (id),
    weekday     SMALLINT     NOT NULL CHECK (weekday BETWEEN 1 AND 7), -- ISO: 1 = Monday
    start_time  TIME         NOT NULL,
    venue       VARCHAR(80)  NOT NULL,
    kind        VARCHAR(12)  NOT NULL CHECK (kind IN ('COMPULSORY', 'OPTIONAL')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  UUID         REFERENCES member (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE training_session (
    id          UUID PRIMARY KEY,
    club_id     UUID         NOT NULL REFERENCES club (id),
    season_id   UUID         REFERENCES season (id),
    pattern_id  UUID         REFERENCES training_pattern (id),
    starts_at   TIMESTAMPTZ  NOT NULL,
    venue       VARCHAR(80)  NOT NULL,
    kind        VARCHAR(12)  NOT NULL CHECK (kind IN ('COMPULSORY', 'OPTIONAL')),
    impromptu   BOOLEAN      NOT NULL DEFAULT FALSE,
    focus       VARCHAR(120),
    status      VARCHAR(10)  NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CLOSED', 'CANCELLED')),
    closed_at   TIMESTAMPTZ,
    closed_by   UUID         REFERENCES member (id),
    created_by  UUID         REFERENCES member (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Generating sessions from a pattern is idempotent.
    CONSTRAINT uq_session_pattern_start UNIQUE (pattern_id, starts_at)
);
CREATE INDEX ix_session_club_start ON training_session (club_id, starts_at);

-- Only "out" (or an explicit "in" after being out) is stored; no row means "I'm in".
CREATE TABLE session_availability (
    session_id  UUID         NOT NULL REFERENCES training_session (id) ON DELETE CASCADE,
    member_id   UUID         NOT NULL REFERENCES member (id),
    status      VARCHAR(3)   NOT NULL CHECK (status IN ('IN', 'OUT')),
    reason      VARCHAR(12),
    set_by      UUID         REFERENCES member (id),
    set_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (session_id, member_id)
);

CREATE TABLE attendance_mark (
    session_id       UUID         NOT NULL REFERENCES training_session (id) ON DELETE CASCADE,
    member_id        UUID         NOT NULL REFERENCES member (id),
    mark             VARCHAR(8)   NOT NULL CHECK (mark IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')),
    marked_by        UUID         REFERENCES member (id),
    -- When the tap happened on the phone (marks can sync later from offline). Newest tap wins.
    client_marked_at TIMESTAMPTZ  NOT NULL,
    synced_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (session_id, member_id)
);
CREATE INDEX ix_attendance_member ON attendance_mark (member_id);
