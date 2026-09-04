// Docker HEALTHCHECK entrypoint.
//
// The runtime image is distroless: no shell, no curl, no wget. The only
// executable available is the node binary, so the check is a tiny node script
// rather than the usual `curl -f localhost/healthz`.
//
// Kubernetes does NOT use this — the chart defines its own HTTP probes. This
// covers `docker run` and any non-k8s runtime.
import http from 'node:http';

const port = Number(process.env.PORT ?? 8080);

const req = http.request(
  { host: '127.0.0.1', port, path: '/healthz', timeout: 2000 },
  (res) => {
    process.exit(res.statusCode === 200 ? 0 : 1);
  },
);

req.on('error', () => process.exit(1));
req.on('timeout', () => {
  req.destroy();
  process.exit(1);
});
req.end();
