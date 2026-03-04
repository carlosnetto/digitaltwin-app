export default {
  async fetch(request: Request, env: any): Promise<Response> {
    const url = new URL(request.url);
    const basePath = env.BASE_PATH || '/digitaltwin-app';

    // Strip the base path prefix
    let pathname = url.pathname;
    if (pathname.startsWith(basePath)) {
      pathname = pathname.slice(basePath.length) || '/';
    }

    // Serve static assets with stripped prefix
    const assetUrl = new URL(request.url);
    assetUrl.pathname = pathname;
    const assetRequest = new Request(assetUrl.toString(), request);
    const assetResponse = await env.ASSETS.fetch(assetRequest);

    // SPA fallback: any 404 serves index.html for client-side routing
    if (assetResponse.status === 404) {
      const fallbackUrl = new URL(request.url);
      fallbackUrl.pathname = '/index.html';
      return env.ASSETS.fetch(new Request(fallbackUrl.toString(), request));
    }

    return assetResponse;
  },
};
