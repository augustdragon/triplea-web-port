// TripleA Web service worker — "your turn" Web Push only (no offline caching).
// Served from the site root so its scope covers the whole app. The control plane sends an
// aes128gcm-encrypted JSON payload {title, body, gameId, url}; the browser decrypts it before the
// push event fires here.

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = {};
  }
  const title = data.title || "TripleA Web";
  const body = data.body || "It's your turn.";
  const url = data.url || "/lobby";
  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      tag: data.gameId || "triplea-turn", // collapse repeats for the same game
      renotify: true,
      data: { url },
    }),
  );
});

// Focus an existing tab for the game if one is open, otherwise open a new one.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "/lobby";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if (client.url.includes(url) && "focus" in client) {
          return client.focus();
        }
      }
      return self.clients.openWindow(url);
    }),
  );
});
