import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
const exec = promisify(execFile);
// Export only the explicitly named synthetic application image. No Docker socket or workspace mount.
const image = 'verified-offers:local';
const directory = await mkdtemp(path.join(tmpdir(), 'vo-image-scan-'));
const container = `vo-scan-${randomUUID()}`;
const output = path.resolve('target/validation/application-image-scan.json');
try {
  await exec('docker', ['save', '--output', path.join(directory, 'image.tar'), image], { timeout: 120_000 });
  const { stdout } = await exec('docker', ['run', '--rm', '--name', container, '--cpus=1', '--memory=1024m',
    '--volume', `${directory}:/scan:ro`, 'aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969', 'image', '--input', '/scan/image.tar',
    '--scanners', 'vuln', '--timeout', '10m', '--format', 'json'], { timeout: 630_000, maxBuffer: 30_000_000 });
  const report = JSON.parse(stdout);
  const findings = (report.Results ?? []).flatMap(result => (result.Vulnerabilities ?? []).map(v => ({
    target: result.Target, id: v.VulnerabilityID, package: v.PkgName, installed: v.InstalledVersion,
    fixed: v.FixedVersion ?? null, severity: v.Severity, url: v.PrimaryURL,
  })));
  const counts = {};
  for (const finding of findings) counts[finding.severity] = (counts[finding.severity] ?? 0) + 1;
  const sanitized = { scannedAt: new Date().toISOString(), image, platform: report.Metadata?.ImageConfig?.architecture,
    scanner: 'trivy:0.74.0', imageId: report.Metadata?.ImageID, counts, findings,
    limitations: 'Point-in-time package findings, not exploitability assessment or production approval.' };
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, JSON.stringify(sanitized, null, 2) + '\n');
  console.log(JSON.stringify({ output, counts, deploymentApproved: false }));
} finally {
  // Exact random scanner name and mkdtemp directory owned by this invocation, never broad cleanup.
  try { await exec('docker', ['rm', '--force', container], { timeout: 10_000 }); } catch { /* --rm may already have removed it. */ }
  await rm(directory, { recursive: true, force: true });
}
