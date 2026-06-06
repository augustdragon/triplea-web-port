import type { CSSProperties, FormEvent } from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { authMethods, devLogin } from "../api/controlPlane";
import type { AuthMethods } from "../api/controlPlane";

/** Map the ?error= a failed OAuth callback redirects with to a readable message. */
const OAUTH_ERRORS: Record<string, string> = {
  not_invited: "That Google account is not on the invite list.",
  oauth_state: "Login session expired — please try again.",
  oauth_failed: "Google sign-in failed — please try again.",
  oauth_no_code: "Google sign-in was cancelled.",
};

/**
 * Login. Offers "Sign in with Google" when the server has OAuth configured, and the dev-login form
 * when dev-login is enabled (dev profile). Both establish the same session cookie. An OAuth callback
 * that fails redirects back here with ?error=… which we surface.
 */
export function Login() {
  const navigate = useNavigate();
  const [methods, setMethods] = useState<AuthMethods | null>(null);
  const [subject, setSubject] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState(() => {
    const code = new URLSearchParams(window.location.search).get("error");
    return code ? (OAUTH_ERRORS[code] ?? "Sign-in failed.") : "";
  });

  useEffect(() => {
    authMethods().then(setMethods);
  }, []);

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
      <div style={card}>
        <h1 style={{ margin: 0, fontSize: 24 }}>TripleA Web</h1>
        {error && <p style={{ color: "#f88", fontSize: 13, margin: 0 }}>{error}</p>}

        {methods?.google && (
          <a style={googleButton} href="/api/oauth/google/login">
            Sign in with Google
          </a>
        )}

        {methods?.google && methods?.devLogin && <div style={divider}>or</div>}

        {methods?.devLogin && (
          <form style={{ display: "flex", flexDirection: "column", gap: 12 }} onSubmit={submit}>
            <p style={{ color: "#999", margin: 0, fontSize: 13 }}>
              Dev login — use an allow-listed subject.
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
            <button style={button} type="submit">
              Sign in
            </button>
          </form>
        )}

        {methods && !methods.google && !methods.devLogin && (
          <p style={{ color: "#999", fontSize: 13 }}>No login methods are configured on the server.</p>
        )}
      </div>
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
const googleButton: CSSProperties = {
  ...button,
  background: "#4285f4",
  borderColor: "#4285f4",
  color: "#fff",
  textAlign: "center",
  textDecoration: "none",
};
const divider: CSSProperties = { color: "#666", fontSize: 12, textAlign: "center" };
