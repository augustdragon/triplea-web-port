-- Web Push ("your turn") subscriptions: one row per browser push endpoint a user has opted into.
-- A single user can have several (one per browser/device), so this is many-rows-per-user. The
-- endpoint is the browser push service's per-subscription URL and is globally unique — UNIQUE on it
-- lets the client re-subscribe idempotently (upsert on conflict) and lets the sender prune a dead
-- endpoint when the push service returns 404/410 (the subscription expired or was revoked).
-- p256dh_key + auth_key are the subscription's public key and shared secret (base64url) the sender
-- needs to encrypt each payload. ON DELETE CASCADE drops a user's subscriptions if the user is
-- removed.
CREATE TABLE push_subscriptions (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint     TEXT        NOT NULL UNIQUE,
    p256dh_key   TEXT        NOT NULL,
    auth_key     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- "Send a push to everything this user is subscribed on" (the turn-transition fan-out) joins on
-- user_id.
CREATE INDEX push_subscriptions_user_id_idx ON push_subscriptions (user_id);
