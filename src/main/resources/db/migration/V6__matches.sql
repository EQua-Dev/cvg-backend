-- M5: matches. Before the game (availability, assisted selection, lineup), after it (result,
-- scorers, Player of the Match vote, opinions).

CREATE TABLE match (
    id                  UUID PRIMARY KEY,
    club_id             UUID         NOT NULL REFERENCES club (id),
    season_id           UUID         REFERENCES season (id),
    opponent            VARCHAR(80)  NOT NULL,
    kickoff_at          TIMESTAMPTZ  NOT NULL,
    meet_at             TIMESTAMPTZ,
    venue               VARCHAR(80)  NOT NULL,
    side                VARCHAR(7)   NOT NULL CHECK (side IN ('HOME', 'AWAY', 'NEUTRAL')),
    match_type          VARCHAR(10)  NOT NULL CHECK (match_type IN ('FRIENDLY', 'TOURNAMENT', 'LEAGUE', 'INTERNAL')),
    team_size           SMALLINT     NOT NULL CHECK (team_size IN (5, 7, 9, 11)),
    game_plan           VARCHAR(3),
    plan_b              VARCHAR(3),
    kit                 VARCHAR(30),
    fee_kobo            BIGINT       CHECK (fee_kobo > 0),
    fee_collection_id   UUID         REFERENCES collection (id),
    notes               VARCHAR(500),
    -- Selection weights for this match (percent per factor); null = club defaults.
    weights             JSONB,
    status              VARCHAR(10)  NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'PLAYED', 'CANCELLED')),
    formation           VARCHAR(10),
    captain_id          UUID         REFERENCES member (id),
    penalty_taker_id    UUID         REFERENCES member (id),
    free_kick_taker_id  UUID         REFERENCES member (id),
    corner_taker_id     UUID         REFERENCES member (id),
    lineup_published_at TIMESTAMPTZ,
    our_score           SMALLINT     CHECK (our_score >= 0),
    their_score         SMALLINT     CHECK (their_score >= 0),
    result_saved_at     TIMESTAMPTZ,
    potm_closes_at      TIMESTAMPTZ,
    created_by          UUID         REFERENCES member (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_match_club_kickoff ON match (club_id, kickoff_at);

-- Same rule as training: no row means "I'm in".
CREATE TABLE match_availability (
    match_id    UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    member_id   UUID         NOT NULL REFERENCES member (id),
    status      VARCHAR(3)   NOT NULL CHECK (status IN ('IN', 'OUT')),
    reason      VARCHAR(12),
    set_by      UUID         REFERENCES member (id),
    set_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (match_id, member_id)
);

-- One row per pitch slot (position 0..n-1 of the formation) or bench spot (100+).
-- A slot holds a member, a guest (name only), or nobody yet.
CREATE TABLE lineup_slot (
    match_id    UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    idx         SMALLINT     NOT NULL,
    member_id   UUID         REFERENCES member (id),
    guest_name  VARCHAR(60),
    PRIMARY KEY (match_id, idx),
    CHECK (member_id IS NULL OR guest_name IS NULL)
);

-- Who actually played, pre-filled from the lineup when the result is entered.
CREATE TABLE match_appearance (
    id          UUID PRIMARY KEY,
    match_id    UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    member_id   UUID         REFERENCES member (id),
    guest_name  VARCHAR(60),
    started     BOOLEAN      NOT NULL,
    -- The slot they started in (e.g. GK, CB), for clean sheets.
    position    VARCHAR(4),
    CHECK ((member_id IS NULL) <> (guest_name IS NULL))
);
CREATE UNIQUE INDEX uq_appearance_member ON match_appearance (match_id, member_id) WHERE member_id IS NOT NULL;

CREATE TABLE match_goal (
    id                UUID PRIMARY KEY,
    match_id          UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    seq               SMALLINT     NOT NULL,
    scorer_member_id  UUID         REFERENCES member (id),
    scorer_guest      VARCHAR(60),
    own_goal          BOOLEAN      NOT NULL DEFAULT FALSE,
    assist_member_id  UUID         REFERENCES member (id),
    assist_guest      VARCHAR(60),
    minute            SMALLINT     CHECK (minute BETWEEN 1 AND 130),
    kind              VARCHAR(10)  CHECK (kind IN ('OPEN_PLAY', 'PENALTY', 'FREE_KICK', 'HEADER'))
);
CREATE INDEX ix_goal_match ON match_goal (match_id);

CREATE TABLE match_card (
    id          UUID PRIMARY KEY,
    match_id    UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    member_id   UUID         NOT NULL REFERENCES member (id),
    colour      VARCHAR(6)   NOT NULL CHECK (colour IN ('YELLOW', 'RED')),
    minute      SMALLINT     CHECK (minute BETWEEN 1 AND 130)
);

-- Anonymous: only totals are ever shown. One vote per squad member, never for themself.
CREATE TABLE potm_vote (
    match_id    UUID         NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    voter_id    UUID         NOT NULL REFERENCES member (id),
    nominee_id  UUID         NOT NULL REFERENCES member (id),
    voted_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (match_id, voter_id),
    CHECK (voter_id <> nominee_id)
);

CREATE TABLE match_opinion (
    match_id       UUID          NOT NULL REFERENCES match (id) ON DELETE CASCADE,
    author_id      UUID          NOT NULL REFERENCES member (id),
    commend_tags   JSONB         NOT NULL DEFAULT '[]',
    commend_text   VARCHAR(280),
    critique_tags  JSONB         NOT NULL DEFAULT '[]',
    critique_text  VARCHAR(280),
    self_rating    SMALLINT      CHECK (self_rating BETWEEN 1 AND 10),
    updated_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (match_id, author_id)
);
