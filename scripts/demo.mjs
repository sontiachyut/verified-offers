import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const child = spawn('java', ['-jar', 'target/verified-offers-0.1.0-SNAPSHOT.jar',
  '--spring.profiles.active=local-demo', '--server.port=0'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
let port;
let launchError;
let exited = false;
const ended = new Promise(resolve => {
  child.once('exit', () => { exited = true; resolve(); });
  child.once('error', error => { launchError = error; exited = true; resolve(); });
});
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
  output = (output + chunk.toString()).slice(-8000);
  port ??= output.match(/Tomcat started on port (\d+)/)?.[1];
});
const stop = () => child.kill('SIGTERM');
process.once('SIGINT', stop);
process.once('SIGTERM', stop);

async function request(method, path, body, expected = 200) {
  const response = await fetch('http://127.0.0.1:' + port + path, {
    method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(5000)
  });
  const data = await response.json();
  assert.equal(response.status, expected, JSON.stringify(data));
  return data;
}

try {
  for (let attempt = 0; !port && attempt < 300 && !exited; attempt++) await delay(100);
  if (launchError) throw launchError;
  if (!port || exited) throw new Error('Demo server did not start. Run ./mvnw verify first.\n' + output);
  await request('GET', '/actuator/health');

  const offer = { tenantId: 'demo', merchantId: 'merchant', offerId: 'headphones', version: 1,
    title: 'Demo headphones', priceMinor: 10000, currency: 'USD', availableQuantity: 2,
    sourceUpdatedAt: new Date(Date.now() - 1000).toISOString(), deleted: false };
  const claim = { tenantId: 'demo', merchantId: 'merchant', offerId: 'headphones', priceMinor: 10000, currency: 'USD' };
  await request('PUT', '/api/v1/offers', offer);
  assert.equal((await request('POST', '/api/v1/verifications', claim)).outcome, 'VERIFIED');
  console.log('PASS: current offer verifies with source provenance.');
  await request('PUT', '/api/v1/offers', { ...offer, version: 2, priceMinor: 12000 });
  assert.equal((await request('POST', '/api/v1/verifications', claim)).outcome, 'MISMATCH');
  console.log('PASS: an old price claim fails after the source price changes.');
  await request('PUT', '/api/v1/offers', offer, 409);
  console.log('PASS: replaying an older version cannot overwrite current facts.');
  await request('PUT', '/api/v1/offers', { ...offer, version: 3, deleted: true });
  assert.equal((await request('POST', '/api/v1/verifications', claim)).outcome, 'DELETED');
  console.log('PASS: a tombstone prevents verification.');

  console.log('Local, volatile reference demo complete. No distributed-scale claim.');
} finally {
  process.removeListener('SIGINT', stop);
  process.removeListener('SIGTERM', stop);
  if (!exited) {
    child.kill('SIGTERM');
    const timer = setTimeout(() => child.kill('SIGKILL'), 5000);
    await ended;
    clearTimeout(timer);
  }
}
