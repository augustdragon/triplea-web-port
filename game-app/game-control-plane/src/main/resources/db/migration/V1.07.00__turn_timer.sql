-- Host-set turn timer: the per-turn time allowance the host chose at table creation, in seconds.
-- 0 = unlimited (no deadline is ever set — an AFK player can stall, like BGA's "no time limit").
-- The active seat's absolute deadline is tracked per-seat in seats.turn_deadline_at (added in V1.03);
-- the game container reads it on (re)boot so a correspondence clock survives a container reap rather
-- than resetting each rehydrate.
ALTER TABLE games ADD COLUMN turn_limit_seconds INTEGER NOT NULL DEFAULT 0;
