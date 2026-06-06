// Client for the control plane's REST API. All calls go to the same origin (Vite proxies /api to
// the control plane on :7000) and send the session cookie via credentials:"include".

export interface User {
  id: number;
  oauthProvider: string;
  oauthSubject: string;
  displayName: string;
  playerChatId: string;
  role: string;
}

export interface SeatView {
  power: string;
  kind: "open" | "human" | "ai";
  ready: boolean;
  connected: boolean;
  ownerName: string | null;
  ownerChatId: string | null;
}

export interface LobbyTable {
  id: string;
  name: string;
  status: string;
  hostName: string;
  hostChatId: string;
  turnLimitSeconds: number;
  seats: SeatView[];
}

export interface CatalogEntry {
  id: string;
  name: string;
  playablePowers: string[];
}

/** A game the signed-in user can enter (hosts or holds a seat in), across any joinable status. */
export interface MyGame {
  id: string;
  name: string;
  status: string;
  seat: string | null;
  isHost: boolean;
}

function req(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch("/api" + path, {
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(init.headers ?? {}) },
    ...init,
  });
}

/** The signed-in user, or null if there is no valid session. */
export async function me(): Promise<User | null> {
  const res = await req("/me");
  return res.ok ? ((await res.json()) as User) : null;
}

export async function devLogin(subject: string, displayName: string): Promise<Response> {
  return req("/dev-login", { method: "POST", body: JSON.stringify({ subject, displayName }) });
}

export async function logout(): Promise<Response> {
  return req("/logout", { method: "POST" });
}

export async function catalog(): Promise<CatalogEntry[]> {
  const res = await req("/catalog");
  return res.ok ? ((await res.json()) as CatalogEntry[]) : [];
}

/** Games the signed-in user hosts or holds a seat in — lets a non-host enter a launched game. */
export async function myGames(): Promise<MyGame[]> {
  const res = await req("/my-games");
  return res.ok ? ((await res.json()) as MyGame[]) : [];
}

export async function createTable(
  gameId: string,
  turnLimitSeconds: number,
): Promise<Response> {
  return req("/games", { method: "POST", body: JSON.stringify({ gameId, turnLimitSeconds }) });
}

/** Turn-timer presets the host picks from (seconds; 0 = unlimited). */
export const TURN_LIMIT_PRESETS: { label: string; seconds: number }[] = [
  { label: "Unlimited", seconds: 0 },
  { label: "30 min", seconds: 30 * 60 },
  { label: "1 hour", seconds: 60 * 60 },
  { label: "1 day", seconds: 24 * 60 * 60 },
  { label: "2 days", seconds: 2 * 24 * 60 * 60 },
  { label: "3 days", seconds: 3 * 24 * 60 * 60 },
  { label: "5 days", seconds: 5 * 24 * 60 * 60 },
  { label: "7 days", seconds: 7 * 24 * 60 * 60 },
  { label: "14 days", seconds: 14 * 24 * 60 * 60 },
];

/** Render a turn-limit value (seconds) as its preset label, for display. */
export function turnLimitLabel(seconds: number): string {
  return TURN_LIMIT_PRESETS.find((p) => p.seconds === seconds)?.label ?? `${seconds}s`;
}

export async function claimSeat(gameId: string, power: string): Promise<Response> {
  return req(`/games/${gameId}/seats/${encodeURIComponent(power)}/claim`, { method: "POST" });
}

export async function releaseSeat(gameId: string, power: string): Promise<Response> {
  return req(`/games/${gameId}/seats/${encodeURIComponent(power)}/release`, { method: "POST" });
}

export async function setReady(gameId: string, power: string, ready: boolean): Promise<Response> {
  return req(`/games/${gameId}/seats/${encodeURIComponent(power)}/ready`, {
    method: "POST",
    body: JSON.stringify({ ready }),
  });
}

export async function launchGame(gameId: string): Promise<Response> {
  return req(`/games/${gameId}/launch`, { method: "POST" });
}

// --- "Your turn" Web Push ---

/** The server's VAPID public key, or null when push is not configured (endpoint returns 404). */
export async function vapidPublicKey(): Promise<string | null> {
  const res = await req("/push/vapid-key");
  if (!res.ok) {
    return null;
  }
  return ((await res.json()) as { key: string }).key;
}

/** Register a browser push subscription (keys flattened from PushSubscription.toJSON()). */
export async function subscribePush(
  endpoint: string,
  p256dh: string,
  auth: string,
): Promise<Response> {
  return req("/push/subscribe", {
    method: "POST",
    body: JSON.stringify({ endpoint, p256dh, auth }),
  });
}

export async function unsubscribePush(endpoint: string): Promise<Response> {
  return req("/push/subscribe", { method: "DELETE", body: JSON.stringify({ endpoint }) });
}
