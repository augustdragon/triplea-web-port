-- Saves: one row per committed phase/step (the game container autosaves after every engine step,
-- which is the finest "never lose a committed move" granularity). bytes_ref points into the save
-- store (a Docker volume now, object storage later), keeping the blob out of Postgres. The
-- position is described as Round / Power / Phase: `round` mirrors the engine round, `power` is
-- whose turn it was (null for any non-power-scoped step), `phase` is the raw engine step name —
-- enough to render "Resume — Round 1 · Japan · Combat Move".
CREATE TABLE saves (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id    UUID        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    round      INTEGER     NOT NULL,
    power      TEXT,
    phase      TEXT        NOT NULL,
    bytes_ref  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lazy rehydration loads the latest save for a game. Order by the monotonic id (robust under
-- concurrent flushes / clock skew, unlike created_at): WHERE game_id = ? ORDER BY id DESC LIMIT 1.
CREATE INDEX saves_game_id_id_idx ON saves (game_id, id DESC);

-- Complete the games <-> saves link now that `saves` exists: games.current_save_id -> saves.id.
-- ON DELETE SET NULL so reaping old saves never orphans the games row.
ALTER TABLE games
    ADD CONSTRAINT games_current_save_fk
    FOREIGN KEY (current_save_id) REFERENCES saves (id) ON DELETE SET NULL;
