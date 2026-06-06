import type { CSSProperties } from "react";
import { useEffect, useState } from "react";
import { disablePush, enablePush, isSubscribed, pushSupported } from "./push";
import { vapidPublicKey } from "./api/controlPlane";

/**
 * Opt-in control for "your turn" Web Push. Renders nothing unless the browser supports push AND the
 * server has push configured (a VAPID key) — so it silently disappears in dev without keys or on
 * unsupported browsers. Toggling subscribes/unsubscribes on a user gesture (required for the
 * permission prompt).
 */
export function NotificationsToggle({ style }: { style?: CSSProperties }) {
  const [available, setAvailable] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!pushSupported()) {
        return;
      }
      const key = await vapidPublicKey();
      if (cancelled || !key) {
        return;
      }
      setAvailable(true);
      const subscribed = await isSubscribed();
      if (!cancelled) {
        setEnabled(subscribed);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (!available) {
    return null;
  }

  const toggle = async () => {
    setBusy(true);
    try {
      if (enabled) {
        await disablePush();
        setEnabled(false);
      } else {
        setEnabled(await enablePush());
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={busy}
      style={style}
      title="Get a notification when it becomes your turn"
    >
      {enabled ? "🔔 Turn alerts on" : "🔕 Enable turn alerts"}
    </button>
  );
}
