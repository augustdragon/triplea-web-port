import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import App from "./App";
import { Login } from "./routes/Login";
import { Lobby } from "./routes/Lobby";

// Three views: login → lobby → game. The Game view is the original single-game client (App); the
// lobby/orchestrator will route it to a specific game's WebSocket in a later milestone. Unknown
// paths fall back to the lobby, which itself redirects to /login when there's no session.
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/lobby" element={<Lobby />} />
        <Route path="/game" element={<App />} />
        <Route path="*" element={<Navigate to="/lobby" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
