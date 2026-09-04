import request from 'supertest';
import { createApp, VERSION } from '../src/app';

const app = createApp();

describe('landing page', () => {
  it('serves HTML a person can read', async () => {
    const res = await request(app).get('/');
    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toMatch(/html/);
    expect(res.text).toContain('gitops-monorepo');
  });

  it('shows the running version, so a canary is visible in a browser', async () => {
    const res = await request(app).get('/');
    expect(res.text).toContain(VERSION);
  });
});

describe('/healthz', () => {
  it('is what the verify stage gates on', async () => {
    const res = await request(app).get('/healthz');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
  });
});

describe('/api/info', () => {
  it('reports version and release mode', async () => {
    const res = await request(app).get('/api/info');
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('version');
    expect(res.body).toHaveProperty('mode');
  });
});

describe('/metrics', () => {
  it('exposes the counter the dashboards query', async () => {
    await request(app).get('/healthz');
    const res = await request(app).get('/metrics');
    expect(res.status).toBe(200);
    expect(res.text).toContain('http_requests_total');
  });

  it('labels series with the version, which the scrape relabel relies on', async () => {
    await request(app).get('/healthz');
    const res = await request(app).get('/metrics');
    expect(res.text).toContain(`version="${VERSION}"`);
  });
});
