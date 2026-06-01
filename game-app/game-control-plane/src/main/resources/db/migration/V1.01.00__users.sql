-- Users: identity comes from an OAuth provider (Google/Discord); no passwords (§5).
-- player_chat_id is a public, server-assigned handle (never a secret) — the stable id that
-- moderation will target later, so it survives display-name changes.
CREATE TABLE users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    oauth_provider  TEXT        NOT NULL,
    oauth_subject   TEXT        NOT NULL,
    display_name    TEXT        NOT NULL,
    player_chat_id  TEXT        NOT NULL,
    role            TEXT        NOT NULL DEFAULT 'player'
                        CHECK (role IN ('player', 'moderator', 'admin')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    -- One account per (provider, subject); access control v1 is the allow-list of these identities.
    CONSTRAINT users_oauth_identity_unique UNIQUE (oauth_provider, oauth_subject),
    CONSTRAINT users_player_chat_id_unique UNIQUE (player_chat_id)
);
