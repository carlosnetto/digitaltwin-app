export default {
  async fetch(request: Request, env: any): Promise<Response> {
    const url = new URL(request.url);
    const basePath = env.BASE_PATH || '/digitaltwin-app';

    // Proxy /digitaltwin-app/api/* to the Java API via Cloudflare Tunnel
    if (url.pathname.startsWith(`${basePath}/api/`)) {
      const apiOrigin = env.API_ORIGIN;
      if (!apiOrigin) {
        return Response.json({ error: 'API_ORIGIN secret not configured' }, { status: 503 });
      }
      const apiPath = url.pathname.slice(basePath.length); // strip /digitaltwin-app
      const apiUrl = `${apiOrigin}${apiPath}${url.search}`;
      try {
        const proxyResponse = await fetch(apiUrl, {
          method: request.method,
          headers: request.headers,
          body: request.method !== 'GET' && request.method !== 'HEAD' ? request.body : undefined,
          redirect: 'follow',
        });
        if (!proxyResponse.ok) {
          const body = await proxyResponse.text();
          let parsed: any = {};
          try { parsed = JSON.parse(body); } catch { parsed = { raw: body.slice(0, 300) }; }
          return Response.json({ ...parsed, _status: proxyResponse.status }, { status: proxyResponse.status });
        }
        return proxyResponse;
      } catch (e: any) {
        return Response.json({ error: `Proxy error: ${e?.message ?? e}` }, { status: 502 });
      }
    }

    // Strip the base path prefix for static assets
    let pathname = url.pathname;
    if (pathname.startsWith(basePath)) {
      pathname = pathname.slice(basePath.length) || '/';
    }

    // Serve static assets with stripped prefix
    const assetUrl = new URL(request.url);
    assetUrl.pathname = pathname;
    const assetResponse = await env.ASSETS.fetch(new Request(assetUrl.toString(), request));

    // Cloudflare ASSETS emits root-relative 301/307 redirects for:
    //   - directory paths without trailing slash  (/erd     → /erd/)
    //   - .html files with pretty-URL stripping   (/f.html  → /f)
    // Re-issue those redirects with the base path prefix so the browser
    // stays under /digitaltwin-app/ instead of jumping to the domain root.
    if (assetResponse.status === 301 || assetResponse.status === 307) {
      const location = assetResponse.headers.get('location') ?? '';
      if (location.startsWith('/')) {
        const redirectUrl = new URL(basePath + location, url.origin);
        return Response.redirect(redirectUrl.toString(), assetResponse.status);
      }
    }

    // SPA fallback: serve the React root for any 404 (client-side routing).
    // Use '/' not '/index.html' — ASSETS would redirect /index.html → / anyway.
    if (assetResponse.status === 404) {
      const fallbackUrl = new URL(request.url);
      fallbackUrl.pathname = '/';
      return env.ASSETS.fetch(new Request(fallbackUrl.toString(), request));
    }

    return assetResponse;
  },
};
