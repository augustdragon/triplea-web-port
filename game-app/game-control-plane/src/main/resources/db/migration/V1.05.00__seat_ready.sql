-- Lobby support on the seats table:
--  * ready: a seat's claimer marks ready; the host can launch once every human seat is ready.
--    Meaningless for open/AI seats but harmless to carry.
--  * seat_order: the power's index in engine turn order, so the lobby lists nations in play order
--    (Japanese, Americans, ...) rather than alphabetically. Set when the table's seats are created.
ALTER TABLE seats ADD COLUMN ready BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE seats ADD COLUMN seat_order INTEGER NOT NULL DEFAULT 0;
