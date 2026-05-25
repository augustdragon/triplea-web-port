import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Phase 1 dev server. Host 0.0.0.0 so a LAN/ZeroTier peer can reach it later.
export default defineConfig({
  plugins: [react()],
  server: { host: true, port: 5173 },
});
