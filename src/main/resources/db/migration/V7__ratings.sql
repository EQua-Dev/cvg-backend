-- M6: peer ratings and FUT cards. Everyone is rated on all 20 attributes; the card shows the
-- 6 that matter for the player's position group. Stat sets can change without a re-vote.

CREATE TABLE stat_set (
    club_id         UUID         NOT NULL REFERENCES club (id),
    position_group  VARCHAR(3)   NOT NULL CHECK (position_group IN ('GK', 'DEF', 'MID', 'ATT')),
    attrs           JSONB        NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, position_group)
);

INSERT INTO stat_set (club_id, position_group, attrs) VALUES
    ('00000000-0000-0000-0000-00000000c0c0', 'GK',  '["REF","HAN","GPO","DIS","COM","OVO"]'),
    ('00000000-0000-0000-0000-00000000c0c0', 'DEF', '["TAC","MRK","AER","PAC","STR","PAS"]'),
    ('00000000-0000-0000-0000-00000000c0c0', 'MID', '["PAS","VIS","CTL","DRI","STA","TAC"]'),
    ('00000000-0000-0000-0000-00000000c0c0', 'ATT', '["FIN","PAC","DRI","MOV","CMP","SHO"]');

CREATE TABLE rating_window (
    id          UUID PRIMARY KEY,
    club_id     UUID         NOT NULL REFERENCES club (id),
    season_id   UUID         REFERENCES season (id),
    title       VARCHAR(80)  NOT NULL,
    opens_at    TIMESTAMPTZ  NOT NULL,
    closes_at   TIMESTAMPTZ  NOT NULL,
    closed_at   TIMESTAMPTZ,
    closed_by   UUID         REFERENCES member (id),
    created_by  UUID         REFERENCES member (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Only one window can be open at a time.
CREATE UNIQUE INDEX uq_window_open ON rating_window (club_id) WHERE closed_at IS NULL;

-- One answer per rater, player and attribute. score NULL = "don't know" (answered, but no rating).
-- Raters are never exposed by the API; they're kept so people can resume and get pre-filled next round.
CREATE TABLE rating (
    window_id   UUID         NOT NULL REFERENCES rating_window (id) ON DELETE CASCADE,
    rater_id    UUID         NOT NULL REFERENCES member (id),
    ratee_id    UUID         NOT NULL REFERENCES member (id),
    attr        VARCHAR(3)   NOT NULL,
    score       SMALLINT     CHECK (score IN (2, 4, 6, 8, 10)),
    rated_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (window_id, rater_id, ratee_id, attr)
);
CREATE INDEX ix_rating_ratee ON rating (window_id, ratee_id);

-- The result of a closed window, one version per player per window. Stats are 30–99 per attribute;
-- OVRs, tier and publishing are worked out on read from the current stat sets.
CREATE TABLE player_card (
    id           UUID PRIMARY KEY,
    window_id    UUID         NOT NULL REFERENCES rating_window (id),
    member_id    UUID         NOT NULL REFERENCES member (id),
    stats        JSONB        NOT NULL,
    peer_counts  JSONB        NOT NULL,
    self_stats   JSONB        NOT NULL,
    position     VARCHAR(4),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (window_id, member_id)
);
CREATE INDEX ix_card_member ON player_card (member_id, created_at DESC);
