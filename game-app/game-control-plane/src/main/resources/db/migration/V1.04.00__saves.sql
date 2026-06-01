-- Saves: one row per committed turn (DB is the source of truth; the container flushes here
-- synchronously on every committed turn — §6.1). bytes_ref points into the save store (a
-- Docker volume now, object storage later), keeping the blob out of Postgres.
CREATE TABLE saves (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id    UUID        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    turn       INTEGER     NOT NULL,
    round      INTEGER     NOT NULL,
    bytes_ref  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lazy rehydration loads the latest save for a game: lookup by game_id ordered by recency.
CREATE INDEX saves_game_id_created_at_idx ON saves (game_id, created_at DESC);

-- Complete the games <-> saves link now that `saves` exists: games.current_save_id -> saves.id.
-- ON DELETE SET NULL so reaping old saves never orphans the games row.
ALTER TABLE games
    ADD CONSTRAINT games_current_save_fk
    FOREIGN KEY (current_save_id) REFERENCES saves (id) ON DELETE SET NULL;
