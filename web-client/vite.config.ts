import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server. Host 0.0.0.0 so a LAN/ZeroTier peer can reach it. The control plane (auth + lobby +
// the per-game WS proxy) runs separately on :7000; proxy its REST + both WebSockets so the browser
// talks to one origin (and the session cookie is same-origin) — matching the prod single-origin
// setup (Caddy → :7000). `/game/*/ws` is the per-game proxy the control plane pipes to each game.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:7000", changeOrigin: true },
      "/ws/lobby": { target: "http://localhost:7000", ws: true, changeOrigin: true },
      "/game": { target: "http://localhost:7000", ws: true, changeOrigin: true },
    },
  },
});
