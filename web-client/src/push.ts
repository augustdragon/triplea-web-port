// Browser side of "your turn" Web Push: register the service worker, subscribe via the Push API
// using the server's VAPID key, and report the subscription to the control plane. All opt-in and
// gated on a user gesture (browsers require it, and auto-prompting is hostile).

import { subscribePush, unsubscribePush, vapidPublicKey } from "./api/controlPlane";

/** Web Push needs service workers, the Push API, and the Notifications API (and a secure context). */
export function pushSupported(): boolean {
  return (
    "serviceWorker" in navigator && "PushManager" in window && "Notification" in window
  );
}

let registration: Promise<ServiceWorkerRegistration> | null = null;

function serviceWorker(): Promise<ServiceWorkerRegistration> {
  if (!registration) {
    registration = navigator.serviceWorker.register("/sw.js");
  }
  return registration;
}

/** Whether this browser currently holds a push subscription. */
export async function isSubscribed(): Promise<boolean> {
  if (!pushSupported()) {
    return false;
  }
  const reg = await serviceWorker();
  return (await reg.pushManager.getSubscription()) !== null;
}

/** Opt in: request permission, subscribe, and register with the server. Returns the new state. */
export async function enablePush(): Promise<boolean> {
  if (!pushSupported()) {
    return false;
  }
  const key = await vapidPublicKey();
  if (!key) {
    return false; // push not configured on the server
  }
  if ((await Notification.requestPermission()) !== "granted") {
    return false;
  }
  const reg = await serviceWorker();
  const subscription =
    (await reg.pushManager.getSubscription()) ??
    (await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(key),
    }));
  const res = await subscribePush(
    subscription.endpoint,
    bytesToBase64Url(subscription.getKey("p256dh")),
    bytesToBase64Url(subscription.getKey("auth")),
  );
  return res.ok;
}

/** Opt out: drop the server record and the browser subscription. */
export async function disablePush(): Promise<void> {
  if (!pushSupported()) {
    return;
  }
  const reg = await serviceWorker();
  const subscription = await reg.pushManager.getSubscription();
  if (subscription) {
    await unsubscribePush(subscription.endpoint);
    await subscription.unsubscribe();
  }
}

function base64UrlToBytes(base64Url: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) {
    bytes[i] = raw.charCodeAt(i);
  }
  return bytes;
}

function bytesToBase64Url(buffer: ArrayBuffer | null): string {
  const bytes = new Uint8Array(buffer ?? new ArrayBuffer(0));
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
