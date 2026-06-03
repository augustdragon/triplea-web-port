import { useEffect, useState } from "react";
import type { LobbyTable } from "../api/controlPlane";

/**
 * Subscribes to the control plane's lobby WebSocket (proxied same-origin at /ws/lobby) and returns
 * the live table list. The server pushes a full snapshot on connect and after every change, so the
 * component just renders whatever arrives. The session cookie authenticates the handshake.
 */
export function useLobbySocket(): LobbyTable[] {
  const [tables, setTables] = useState<LobbyTable[]>([]);

  useEffect(() => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws/lobby`);
    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data) as { type: string; tables: LobbyTable[] };
      if (msg.type === "lobby") {
        setTables(msg.tables);
      }
    };
    return () => ws.close();
  }, []);

  return tables;
}
