-- M7: full player profiles (questionnaire + ratings + match data), peer role votes,
-- the coach's final role, chemistry rules and questionnaire rounds.

-- "What role suits [Name] best?" — one extra tap per teammate in a rating window. Secret like ratings.
CREATE TABLE role_vote (
    window_id   UUID         NOT NULL REFERENCES rating_window (id) ON DELETE CASCADE,
    rater_id    UUID         NOT NULL REFERENCES member (id),
    ratee_id    UUID         NOT NULL REFERENCES member (id),
    role_code   VARCHAR(4)   NOT NULL,
    voted_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (window_id, rater_id, ratee_id),
    CHECK (rater_id <> ratee_id)
);

-- The coach's call when self and squad disagree. No row = use the questionnaire's role.
CREATE TABLE player_role (
    member_id   UUID PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    role_code   VARCHAR(4)   NOT NULL,
    set_by      UUID         REFERENCES member (id),
    set_at      TIMESTAMPTZ  NOT NULL
);

-- Pairings that work (GREEN), are risky (AMBER) or clash (RED). Roles are codes, or a whole
-- group as "GK*", "DEF*", "MID*", "ATT*". NEIGHBOURS = next to each other on the pitch;
-- TEAM = anywhere in the XI. unless_role cancels a TEAM rule when that role is in the XI.
CREATE TABLE chemistry_rule (
    id           UUID PRIMARY KEY,
    club_id      UUID         NOT NULL REFERENCES club (id),
    role_a       VARCHAR(5)   NOT NULL,
    role_b       VARCHAR(5)   NOT NULL,
    link         VARCHAR(5)   NOT NULL CHECK (link IN ('GREEN', 'AMBER', 'RED')),
    scope        VARCHAR(10)  NOT NULL DEFAULT 'NEIGHBOURS' CHECK (scope IN ('NEIGHBOURS', 'TEAM')),
    plan         VARCHAR(3),
    unless_role  VARCHAR(5),
    note         VARCHAR(120),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO chemistry_rule (id, club_id, role_a, role_b, link, scope, plan, unless_role, note) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'BWM', 'DLP', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Ball-winner frees the playmaker'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'BWM', 'AP',  'GREEN', 'NEIGHBOURS', NULL, NULL, 'Ball-winner covers the playmaker'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'STP', 'CVR', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'One steps out, one covers'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'BPD', 'STP', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Builder plus enforcer'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'BPD', 'CVR', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Builder plus cover'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'TGT', 'WNG', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Crosses for the big man'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'TGT', 'INF', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Knock-downs for the runner'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'POA', 'AP',  'GREEN', 'NEIGHBOURS', NULL, NULL, 'Through balls for the finisher'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'POA', 'LNK', 'GREEN', 'NEIGHBOURS', NULL, NULL, 'Link-up feeds the finisher'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'RUN', 'DLP', 'GREEN', 'TEAM',       NULL, NULL, 'Long balls in behind'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'SWK', 'DEF*', 'GREEN', 'NEIGHBOURS', 'PRS', NULL, 'Sweeper behind a high line'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'STP', 'STP', 'AMBER', 'NEIGHBOURS', NULL, NULL, 'Both step out, space behind'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'AP',  'AP',  'AMBER', 'NEIGHBOURS', NULL, NULL, 'No cover in midfield'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'AFB', 'AFB', 'AMBER', 'TEAM',       NULL, 'BWM', 'Both full-backs bomb on, nobody screens'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'TGT', 'TGT', 'RED',   'NEIGHBOURS', NULL, NULL, 'Two target men get in each other''s way'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'SHS', 'DEF*', 'RED',  'NEIGHBOURS', 'PRS', NULL, 'Line-bound keeper behind a high line');

-- The coach can ask everyone to redo the questionnaire (e.g. each season).
CREATE TABLE profiling_round (
    id          UUID PRIMARY KEY,
    club_id     UUID         NOT NULL REFERENCES club (id),
    opened_at   TIMESTAMPTZ  NOT NULL,
    opened_by   UUID         REFERENCES member (id)
);
