const CACHE_NAME = 'digitaltwin-app-v1';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', e => e.waitUntil(clients.claim()));

self.addEventListener('fetch', e => {
  const { request } = e;
  const url = new URL(request.url);

  // Skip non-GET and cross-origin (API calls to Gemini, etc.)
  if (request.method !== 'GET' || url.origin !== location.origin) return;

  // Navigation requests: network-first, fall back to cached index.html (SPA)
  if (request.mode === 'navigate') {
    e.respondWith(
      fetch(request).catch(() => caches.match('/index.html'))
    );
    return;
  }

  // Static assets: stale-while-revalidate
  e.respondWith(
    caches.open(CACHE_NAME).then(cache =>
      cache.match(request).then(cached => {
        const network = fetch(request).then(response => {
          if (response.ok) cache.put(request, response.clone());
          return response;
        });
        return cached || network;
      })
    )
  );
});
