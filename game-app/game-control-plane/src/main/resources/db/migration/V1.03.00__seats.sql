-- Seats: one row per power per game. user_id is the AUTHENTICATED claimer (never a
-- client-supplied name) — this is what makes in-game decision routing a real security
-- boundary (replacing the trusted display-name in the game container's setup phase).
-- kind distinguishes a human-claimed seat from an AI seat or a still-open seat.
CREATE TABLE seats (
    game_id          UUID        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    power_name       TEXT        NOT NULL,
    user_id          BIGINT      REFERENCES users (id),
    kind             TEXT        NOT NULL DEFAULT 'open'
                        CHECK (kind IN ('human', 'ai', 'open')),
    connected        BOOLEAN     NOT NULL DEFAULT false,
    turn_deadline_at TIMESTAMPTZ,
    PRIMARY KEY (game_id, power_name)
);

-- "Which seats does this user hold" (lobby + reconnect authorization) joins on user_id.
CREATE INDEX seats_user_id_idx ON seats (user_id);
