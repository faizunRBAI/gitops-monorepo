import { createApp } from './app';

const port = Number(process.env.PORT ?? 8080);
const app = createApp();

const server = app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`listening on :${port}`);
});

// Kubernetes sends SIGTERM and waits terminationGracePeriodSeconds. Without
// this handler PID 1 ignores it and every rollout step stalls for the full
// grace period before the pod is killed.
function shutdown(signal: string): void {
  // eslint-disable-next-line no-console
  console.log(`${signal} received, draining`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
