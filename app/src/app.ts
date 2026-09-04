import express, { Express, Request, Response, NextFunction } from 'express';
import client from 'prom-client';

// Injected by the chart from the Rollout's pod spec, so the page and the
// metrics agree on which version is answering.
export const VERSION = process.env.APP_VERSION ?? 'dev';
export const RELEASE_MODE = process.env.RELEASE_MODE ?? 'plain';
export const POD_NAME = process.env.POD_NAME ?? 'local';

const registry = new client.Registry();
registry.setDefaultLabels({ version: VERSION });
client.collectDefaultMetrics({ register: registry });

const httpRequests = new client.Counter({
  name: 'http_requests_total',
  help: 'Total HTTP requests.',
  labelNames: ['method', 'route', 'status'] as const,
  registers: [registry],
});

const httpDuration = new client.Histogram({
  name: 'http_request_duration_seconds',
  help: 'HTTP request duration in seconds.',
  labelNames: ['method', 'route', 'status'] as const,
  buckets: [0.005, 0.01, 0.05, 0.1, 0.3, 1, 3],
  registers: [registry],
});

function landingPage(): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>gitops-monorepo</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; min-height:100vh; display:grid; place-items:center;
         font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
         background:#0d1117; color:#e6edf3; }
  .card { max-width:44rem; padding:2.5rem; border:1px solid #30363d;
          border-radius:12px; background:#161b22; }
  h1 { margin:0 0 .25rem; font-size:1.5rem; }
  p.sub { margin:0 0 1.75rem; color:#8b949e; }
  dl { display:grid; grid-template-columns:auto 1fr; gap:.6rem 1.25rem; margin:0 0 1.75rem; }
  dt { color:#8b949e; }
  dd { margin:0; }
  .tag { display:inline-block; padding:.15rem .55rem; border-radius:999px;
         background:#1f6feb22; border:1px solid #1f6feb; color:#79c0ff; }
  ul { margin:0; padding-left:1.1rem; color:#8b949e; line-height:1.8; }
  a { color:#79c0ff; }
</style>
</head>
<body>
  <main class="card">
    <h1>gitops-monorepo</h1>
    <p class="sub">One repository: application, chart, cluster config, pipeline.</p>
    <dl>
      <dt>version</dt><dd><span class="tag">${VERSION}</span></dd>
      <dt>release mode</dt><dd>${RELEASE_MODE}</dd>
      <dt>pod</dt><dd>${POD_NAME}</dd>
    </dl>
    <ul>
      <li>CI built and pushed this image, then patched the ArgoCD Application's
          Helm parameter &mdash; it did not commit to Git.</li>
      <li>ArgoCD reconciled the cluster to match. <code>selfHeal</code> is on.</li>
      <li><a href="/healthz">/healthz</a> &middot; <a href="/metrics">/metrics</a></li>
    </ul>
  </main>
</body>
</html>`;
}

export function createApp(): Express {
  const app = express();
  app.disable('x-powered-by');

  app.use((req: Request, res: Response, next: NextFunction) => {
    const end = httpDuration.startTimer();
    res.on('finish', () => {
      // req.route is undefined for unmatched paths; bucketing them under the
      // raw path would give unbounded metric cardinality.
      const route = req.route?.path ?? 'unmatched';
      const labels = { method: req.method, route, status: String(res.statusCode) };
      httpRequests.inc(labels);
      end(labels);
    });
    next();
  });

  app.get('/', (_req: Request, res: Response) => {
    res.type('html').send(landingPage());
  });

  app.get('/healthz', (_req: Request, res: Response) => {
    res.json({ status: 'ok', version: VERSION, mode: RELEASE_MODE });
  });

  app.get('/api/info', (_req: Request, res: Response) => {
    res.json({ version: VERSION, mode: RELEASE_MODE, pod: POD_NAME });
  });

  app.get('/metrics', async (_req: Request, res: Response) => {
    res.set('Content-Type', registry.contentType);
    res.send(await registry.metrics());
  });

  return app;
}
