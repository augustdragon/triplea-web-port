import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server. Host 0.0.0.0 so a LAN/ZeroTier peer can reach it. The control plane (auth + lobby)
// runs separately on :7000; proxy its REST + lobby WebSocket so the browser talks to one origin
// (and the session cookie is same-origin). The per-game WebSocket (:8080) is dialed directly by the
// Game view and is not proxied here.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:7000", changeOrigin: true },
      "/ws/lobby": { target: "http://localhost:7000", ws: true, changeOrigin: true },
    },
  },
});
