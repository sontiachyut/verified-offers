import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { createServer } from 'vite';

// Own a new synthetic Compose project. Never reuse or delete an existing stack.
// Root Compose supplies the same digest-pinned images as the backend runbooks.
const root = fileURLToPath(new URL('../../', import.meta.url));
const consoleDir = fileURLToPath(new URL('../', import.meta.url));
const project = `vo-console-${randomUUID().slice(0, 8)}`;
const env = {
  ...process.env,
  APP_DATABASE_USER: 'console_test',
  APP_DATABASE_PASSWORD: randomUUID(),
  APP_DATABASE_URL: 'jdbc:postgresql://127.0.0.1:5541/offers',
};
const compose = [
  'compose',
  '--project-name',
  project,
  '--profile',
  'messaging',
  '--profile',
  'search',
];
let app;
let appEnded;
let server;
let started = false;
let appOutput = '';
let port;
const command = (binary, args, options = {}) =>
  new Promise((resolve, reject) => {
    const child = spawn(binary, args, { cwd: root, env, stdio: 'inherit', ...options });
    child.once('error', reject);
    child.once('exit', (code) =>
      code === 0 ? resolve() : reject(new Error(`${binary} exited with ${code}`)),
    );
  });
async function shutdown() {
  await server?.close();
  if (app && app.exitCode === null) {
    app.kill('SIGTERM');
    const timer = setTimeout(() => app.kill('SIGKILL'), 35_000);
    await appEnded;
    clearTimeout(timer);
  }
  if (started) await command('docker', [...compose, 'down', '--volumes', '--timeout', '15']);
}
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM'])
  process.once(signal, () => {
    interrupted = true;
    app?.kill('SIGTERM');
  });
try {
  console.log('Starting isolated synthetic dependencies; no existing project is reused.');
  await command('docker', ['info', '--format', '{{.ServerVersion}}']);
  started = true;
  await command('docker', [...compose, 'up', '-d', '--wait', '--wait-timeout', '120']);
  if (interrupted) throw new Error('Interrupted.');
  await command('docker', [
    ...compose,
    'exec',
    '-T',
    'kafka',
    '/opt/kafka/bin/kafka-topics.sh',
    '--bootstrap-server',
    'localhost:19092',
    '--create',
    '--topic',
    'offers.v1',
    '--partitions',
    '3',
    '--replication-factor',
    '1',
  ]);
  const mapping = JSON.parse(
    await readFile(`${root}/src/main/resources/search/mapping.json`, 'utf8'),
  );
  mapping.aliases = { 'offers-console': { is_write_index: true } };
  const index = await fetch('http://127.0.0.1:9201/offers-console-v1', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(mapping),
    signal: AbortSignal.timeout(10_000),
  });
  if (!index.ok)
    throw new Error(`Cannot initialize the isolated search index: HTTP ${index.status}`);
  console.log('Starting packaged API with feed, publisher and indexer enabled.');
  app = spawn(
    'java',
    [
      '-XX:ActiveProcessorCount=2',
      '-Xmx384m',
      '-jar',
      'target/verified-offers-0.1.0-SNAPSHOT.jar',
      '--spring.profiles.active=postgres-local',
      '--server.port=0',
      '--offers.feeds.enabled=true',
      '--offers.feeds.worker-enabled=true',
      '--offers.publisher.enabled=true',
      '--offers.publisher.bootstrap-servers=127.0.0.1:9094',
      '--offers.search.enabled=true',
      '--offers.search.endpoint=http://127.0.0.1:9201',
      '--offers.search.index=offers-console',
      '--offers.indexer.enabled=true',
      '--offers.indexer.bootstrap-servers=127.0.0.1:9094',
      '--offers.indexer.group=console-acceptance',
    ],
    { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  appEnded = new Promise((resolve) => {
    app.once('exit', resolve);
    app.once('error', resolve);
  });
  for (const stream of [app.stdout, app.stderr])
    stream.on('data', (chunk) => {
      appOutput = (appOutput + chunk).slice(-8000);
      port ??= appOutput.match(/Tomcat started on port (\d+)/)?.[1];
    });
  let ready = false;
  for (let attempt = 0; attempt < 120 && !interrupted && app.exitCode === null; attempt++) {
    if (port) {
      const health = await fetch(`http://127.0.0.1:${port}/actuator/health`, {
        signal: AbortSignal.timeout(1000),
      }).catch(() => null);
      if (health?.ok) {
        ready = true;
        break;
      }
    }
    await delay(250);
  }
  if (!ready) throw new Error(`API startup failed. ${appOutput}`);
  server = await createServer({
    configFile: `${consoleDir}/vite.config.ts`,
    root: consoleDir,
    server: {
      host: '127.0.0.1',
      port: 0,
      strictPort: false,
      proxy: { '/api': { target: `http://127.0.0.1:${port}`, changeOrigin: false } },
    },
  });
  await server.listen();
  const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
  const page = await fetch(origin);
  if (!page.ok) throw new Error('The local console route did not compile.');
  console.log('Running real-API React interaction walkthrough through the local proxy.');
  await command(
    process.execPath,
    ['node_modules/vitest/vitest.mjs', 'run', '--config', 'vitest.integration.ts'],
    { cwd: consoleDir, env: { ...env, CONSOLE_TEST_ORIGIN: origin, DEBUG_PRINT_LIMIT: '1200' } },
  );
  console.log(
    'PASS: synthetic console admission, row evidence, replay, search pages and source deletion.',
  );
} finally {
  await shutdown();
  console.log('Removed only this test run’s synthetic Compose containers and volumes.');
}
