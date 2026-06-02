-- Games: the lobby/active/paused/finished lifecycle row, and the orchestration handle
-- (container_id, ws_endpoint) the control plane fills in when it spawns a game container.
-- id is a UUID so it can sit in URLs (/api/games/:id/connect) without exposing a sequence.
--
-- Live position is modelled as Round / Power / Phase (the unambiguous TripleA + rulebook
-- vocabulary): `round` mirrors the engine's getSequence().getRound() (one full cycle of all
-- powers; the "1" in community shorthand like "J1"), `current_power` is whose turn it is, and
-- `current_phase` is the raw engine step display name. The latter two are null while status is
-- 'lobby' (no game running yet). `updated_at` is set by the app on each write (no trigger).
-- current_save_id points at the chosen resume save; its FK is added in V1.04 once `saves` exists.
CREATE TABLE games (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    map_xml         TEXT        NOT NULL,
    edition         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'lobby'
                        CHECK (status IN ('lobby', 'active', 'paused', 'finished')),
    container_id    TEXT,
    ws_endpoint     TEXT,
    current_save_id BIGINT,
    round           INTEGER     NOT NULL DEFAULT 0,
    current_power   TEXT,
    current_phase   TEXT,
    created_by      BIGINT      NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Public lobby list filters on status; "my games" / ownership joins on created_by.
CREATE INDEX games_status_idx ON games (status);
CREATE INDEX games_created_by_idx ON games (created_by);
