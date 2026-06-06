import { useEffect, useRef } from "react";
import type { BattleEvent } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * A historical battle record: one line per completed battle, grouped by game round and then by the
 * attacking nation. Fed by the server's {kind:"result"} events. Auto-scrolls to the newest result.
 * (Live, in-battle detail for a retreat decision is shown in the RetreatPanel, not here — this stays
 * a compact after-the-fact record.)
 */
export function BattleLog({ events }: { events: BattleEvent[] }) {
  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [events]);

  // Group: round -> attacking nation -> battles, preserving first-seen order within each level.
  const rounds = new Map<number, Map<string, BattleEvent[]>>();
  for (const e of events) {
    if (e.kind !== "result") continue;
    if (!rounds.has(e.gameRound)) rounds.set(e.gameRound, new Map());
    const nations = rounds.get(e.gameRound)!;
    if (!nations.has(e.attacker)) nations.set(e.attacker, []);
    nations.get(e.attacker)!.push(e);
  }
  const roundKeys = [...rounds.keys()].sort((a, b) => a - b);

  return (
    <div style={{ height: "100%", overflowY: "auto", padding: "8px 12px", fontSize: 12 }}>
      <div
        style={{
          fontSize: 10,
          color: "#778",
          marginBottom: 4,
          textTransform: "uppercase",
          letterSpacing: 0.6,
        }}
      >
        Battle log
      </div>
      {roundKeys.length === 0 ? (
        <div style={{ color: "#667" }}>No battles yet this game.</div>
      ) : (
        roundKeys.map((round) => (
          <div key={round} style={{ marginBottom: 6 }}>
            <div
              style={{
                color: "#cdb98a",
                borderBottom: "1px solid #3a4654",
                paddingBottom: 1,
                marginBottom: 2,
              }}
            >
              ═ Round {round} ═
            </div>
            {[...rounds.get(round)!.entries()].map(([nation, battles]) => (
              <div key={nation} style={{ marginBottom: 3 }}>
                <div style={{ color: "#9fb6c9", paddingLeft: 4 }}>{nation}</div>
                {battles.map((b) => (
                  <BattleResult key={b.id} battle={b} />
                ))}
              </div>
            ))}
          </div>
        ))
      )}
      <div ref={endRef} />
    </div>
  );
}

function BattleResult({ battle }: { battle: BattleEvent }) {
  return (
    <div style={{ paddingLeft: 14, marginTop: 1 }}>
      <div>
        ⚔ <b>{displayTerritory(battle.location)}</b>{" "}
        <span style={{ color: "#9fb6c9" }}>vs {battle.defender}</span> —{" "}
        <span style={{ color: "#7c7" }}>{battle.result}</span>
      </div>
      {(battle.attackerLosses || battle.defenderLosses) && (
        <div style={{ paddingLeft: 14, color: "#e89", fontSize: 11 }}>
          {battle.attacker} lost {battle.attackerLosses || "nothing"} · {battle.defender} lost{" "}
          {battle.defenderLosses || "nothing"}
        </div>
      )}
    </div>
  );
}
