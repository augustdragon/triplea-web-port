import type { CSSProperties, FormEvent } from "react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { devLogin } from "../api/controlPlane";

/**
 * Dev login: posts a subject + display name to /api/dev-login (a synthetic Google identity) and, on
 * success, goes to the lobby. Real Google/Discord OAuth replaces this behind the same session-cookie
 * flow later; for now the subject must be on the control plane's allow-list.
 */
export function Login() {
  const navigate = useNavigate();
  const [subject, setSubject] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    const id = subject.trim() || "player";
    const res = await devLogin(id, displayName.trim() || id);
    if (res.ok) {
      navigate("/lobby");
    } else {
      setError(res.status === 403 ? "That identity is not on the allow-list." : "Login failed.");
    }
  }

  return (
    <div style={page}>
      <form style={card} onSubmit={submit}>
        <h1 style={{ margin: 0, fontSize: 24 }}>TripleA Web</h1>
        <p style={{ color: "#999", marginTop: 4, fontSize: 13 }}>
          Dev login — Google/Discord OAuth lands later. Use an allow-listed subject.
        </p>
        <label style={label}>
          Subject (OAuth id)
          <input
            style={input}
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="alice"
            autoFocus
          />
        </label>
        <label style={label}>
          Display name
          <input
            style={input}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Alice"
          />
        </label>
        {error && <p style={{ color: "#f88", fontSize: 13 }}>{error}</p>}
        <button style={button} type="submit">
          Sign in
        </button>
      </form>
    </div>
  );
}

const page: CSSProperties = {
  minHeight: "100vh",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  background: "#1a1a1a",
  fontFamily: "sans-serif",
  color: "#ddd",
};
const card: CSSProperties = {
  background: "#242424",
  border: "1px solid #444",
  borderRadius: 8,
  padding: 28,
  width: 320,
  display: "flex",
  flexDirection: "column",
  gap: 12,
};
const label: CSSProperties = { display: "flex", flexDirection: "column", gap: 4, fontSize: 13 };
const input: CSSProperties = {
  background: "#1a1a1a",
  border: "1px solid #555",
  color: "#eee",
  borderRadius: 4,
  padding: "6px 8px",
  fontSize: 14,
};
const button: CSSProperties = {
  background: "#2d8",
  border: "1px solid #2d8",
  color: "#052",
  borderRadius: 4,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
  fontSize: 14,
};
