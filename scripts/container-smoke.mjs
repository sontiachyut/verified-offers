import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
const exec = promisify(execFile);
const name = `vo-smoke-${randomUUID()}`;
const docker = async (...args) => (await exec('docker', args, { timeout: 30_000, maxBuffer: 256_000 })).stdout.trim();
let created = false;
try {
  await docker('run', '--name', name, '--detach', '--read-only', '--tmpfs', '/tmp:size=64m,mode=1777',
    '--cpus=1.5', '--memory=640m', '--pids-limit=128', '--cap-drop=ALL', '--security-opt=no-new-privileges:true',
    '--publish', '127.0.0.1::8081', 'verified-offers:local', '--spring.profiles.active=local-demo', '--server.address=0.0.0.0');
  created = true;
  const binding = await docker('port', name, '8081/tcp');
  assert.match(binding, /^127\.0\.0\.1:\d+$/);
  const base = `http://${binding}`;
  const request = async (path, method = 'GET', body) => fetch(base + path, {
    method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(3000),
  });
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    try { if ((await request('/actuator/health')).ok) { ready = true; break; } } catch { /* bounded startup only */ }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.ok(ready, 'Container did not become healthy');
  assert.equal(await docker('inspect', '--format', '{{.Config.User}}', name), '10001:10001');
  assert.equal(await docker('inspect', '--format', '{{.HostConfig.ReadonlyRootfs}}', name), 'true');
  const offer = { tenantId: 'smoke', merchantId: 'synthetic', offerId: 'keyboard', version: 1,
    title: 'Synthetic keyboard', priceMinor: 1999, currency: 'USD', availableQuantity: 1,
    sourceUpdatedAt: new Date(Date.now() - 1000).toISOString(), deleted: false };
  assert.equal((await request('/api/v1/offers', 'PUT', offer)).status, 200);
  const claim = { tenantId: offer.tenantId, merchantId: offer.merchantId, offerId: offer.offerId, priceMinor: 1999, currency: 'USD' };
  assert.equal((await (await request('/api/v1/verifications', 'POST', claim)).json()).outcome, 'VERIFIED');
  assert.equal((await request('/api/v1/offers', 'PUT', { ...offer, priceMinor: 1 })).status, 409);
  console.log(JSON.stringify({ result: 'passed', nonRoot: true, readOnly: true, loopbackOnly: true,
    ingestion: true, verification: true, conflict: true, mode: 'volatile-local-demo' }));
} finally {
  if (created) await docker('rm', '--force', name); // Only the random container created above; no volumes or other services.
}
