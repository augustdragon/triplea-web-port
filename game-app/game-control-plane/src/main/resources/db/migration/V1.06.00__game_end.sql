-- How a game ended, recorded when the container reports 'finished'. Without this, status='finished'
-- can't tell a real victory from a round-cap / stuck / error stop. end_reason is a GameEndReason
-- name (VICTORY, CONCEDED, ABANDONED, ERROR, STUCK, ROUND_CAP, HOST_ENDED); winner is a
-- comma-separated list of winning powers, NULL when there is no winner.
ALTER TABLE games ADD COLUMN end_reason TEXT;
ALTER TABLE games ADD COLUMN winner     TEXT;
