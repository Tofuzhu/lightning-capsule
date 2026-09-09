/**
 * Lightning Capsule — Service Worker
 * SW version: v2 (Phase 2 PWA shell)
 *
 * Strategy:
 *   - Precache the app shell (index.html, manifest, icons) on install.
 *   - Navigation requests: network-first, fall back to cached index.html so the
 *     shell opens offline.
 *   - Same-origin static assets (manifest / icons): cache-first.
 *   - /api/* requests: never touched by the SW (always hit the network).
 */
const SW_VERSION = "v2";
const CACHE_NAME = `lightning-capsule-${SW_VERSION}`;
const SHELL = ["/", "/index.html", "/manifest.webmanifest", "/icon-192.png", "/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Let API calls pass straight through — no caching, no interception.
  if (url.origin === self.location.origin && url.pathname.startsWith("/api/")) return;

  // App-shell navigations: network-first with offline fallback.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((resp) => {
          const copy = resp.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put("/index.html", copy));
          return resp;
        })
        .catch(() => caches.match("/index.html").then((r) => r || caches.match("/"))),
    );
    return;
  }

  // Other same-origin GETs (icons, manifest): cache-first.
  if (url.origin === self.location.origin) {
    event.respondWith(
      caches.match(request).then(
        (cached) =>
          cached ||
          fetch(request).then((resp) => {
            const copy = resp.clone();
            if (resp.ok) caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
            return resp;
          }),
      ),
    );
  }
});
