import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, ArrowRight, FileUp, RefreshCw } from 'lucide-react';
import { feedUrl, identifier, message, prepareUpload, request, submitUpload, terminal, type Action, type Job, type Jobs, type Rows, type Scope, type Upload } from './api';
import { ErrorNotice, Modal, Status, Time } from './ui';

export function FeedWorkspace() {
  const [tenant, setTenant] = useState('demo');
  const [merchant, setMerchant] = useState('synthetic');
  const [scope, setScope] = useState<Scope | null>(null);
  const [jobs, setJobs] = useState<Jobs | null>(null);
  const [busy, setBusy] = useState(false);
  const [locked, setLocked] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [loadedAt, setLoadedAt] = useState<string | null>(null);
  const epoch = useRef(0);
  useEffect(() => () => { epoch.current++; }, []);
  async function load(next: Scope, after?: string | null) {
    const token = ++epoch.current;
    setBusy(true); setError(null);
    if (!after) { setScope(next); setJobs(null); setSelected(null); setLoadedAt(null); }
    try {
      const result = await request<Jobs>(`${feedUrl(next)}?limit=20${after ? `&after=${encodeURIComponent(after)}` : ''}`);
      if (token === epoch.current) { setJobs(previous => ({ jobs: after ? [...(previous?.jobs ?? []), ...result.jobs] : result.jobs, nextAfter: result.nextAfter })); setLoadedAt(new Date().toISOString()); }
    } catch (failure) { if (token === epoch.current) setError(failure); }
    finally { if (token === epoch.current) setBusy(false); }
  }
  function submit(event: FormEvent) { event.preventDefault(); void load({ tenant, merchant }); }
  return <>
    <section className="panel"><form className="search-form" onSubmit={submit}><label>Feed tenant<input required pattern={identifier} maxLength={64} value={tenant} disabled={locked || busy} onChange={e => setTenant(e.target.value)} /></label><label className="grow">Merchant<input required pattern={identifier} maxLength={64} value={merchant} disabled={locked || busy} onChange={e => setMerchant(e.target.value)} /></label><button className="primary" disabled={busy || locked}>{busy ? 'Loading jobs…' : 'Open feed scope'}</button></form><p className="caption">Use synthetic feeds only. Completed ingestion does not mean the search index has caught up.</p></section>
    {error != null && <ErrorNotice>{message(error)}{scope && <button disabled={busy} onClick={() => void load(scope, jobs?.nextAfter)}>Retry list request</button>}</ErrorNotice>}
    {!scope && <section className="empty"><FileUp size={30} aria-hidden="true" /><h2>Follow a feed from input to receipt.</h2><p>Open a tenant and merchant scope to upload a bounded feed,<br />inspect row outcomes, or recover paused work.</p></section>}
    {scope && <>
      <div className="section-heading workspace-section"><h2>Active scope <span className="mono">{scope.tenant} / {scope.merchant}</span></h2></div>
      <UploadPanel key={`${scope.tenant}/${scope.merchant}`} scope={scope} onLock={setLocked} onAccepted={job => { setSelected(job.id); setJobs(previous => previous ? { ...previous, jobs: [job, ...previous.jobs.filter(value => value.id !== job.id)] } : { jobs: [job], nextAfter: null }); }} />
      {selected ? <JobDetail key={`${scope.tenant}/${scope.merchant}/${selected}`} scope={scope} id={selected} onBack={() => setSelected(null)} /> : <section className="workspace-section" aria-busy={busy}><div className="section-heading"><h2>Feed jobs <span className="muted">/ {jobs?.jobs.length ?? 0} loaded</span></h2><button disabled={busy} onClick={() => void load(scope)}><RefreshCw size={14} />Refresh list</button></div><p className="caption">Live UUID-keyset listing, not creation order. Refresh to discover new jobs. {loadedAt && <>Last loaded <Time value={loadedAt} />.</>}</p>
        {jobs?.jobs.length === 0 && <div className="empty compact"><h2>No jobs in this scope.</h2><p>Upload a synthetic NDJSON feed to create the first receipt trail.</p></div>}
        {!!jobs?.jobs.length && <div className="table-wrap"><table><caption className="sr-only">Feed jobs in the active scope</caption><thead><tr><th>Feed source / job</th><th>State</th><th className="numeric">Processed</th><th className="numeric">Rejected</th><th>Created</th><th><span className="sr-only">Inspect job</span></th></tr></thead><tbody>{jobs.jobs.map(job => <tr key={job.id}><td><strong>{job.source}</strong><small className="mono">{job.id}</small></td><td><Status value={job.state} /></td><td className="numeric mono">{job.processed} / {job.total}</td><td className="numeric mono">{job.rejected}</td><td><Time value={job.createdAt} /></td><td><button className="text-button" aria-label={`Inspect job ${job.id}`} onClick={() => setSelected(job.id)}>Inspect <ArrowRight size={14} /></button></td></tr>)}</tbody></table></div>}
        {jobs?.nextAfter && <div className="pagination"><p>More retained jobs available.</p><button disabled={busy} onClick={() => void load(scope, jobs.nextAfter)}>Load more jobs</button></div>}
      </section>}
    </>}
  </>;
}

function UploadPanel({ scope, onLock, onAccepted }: { scope: Scope; onLock: (locked: boolean) => void; onAccepted: (job: Job) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [source, setSource] = useState('synthetic-fixture');
  const [key, setKey] = useState(() => crypto.randomUUID() as string);
  const [upload, setUpload] = useState<Upload | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [accepted, setAccepted] = useState<{ created: boolean; job: Job } | null>(null);
  const [resetting, setResetting] = useState(false);
  const [fileKey, setFileKey] = useState(0);
  const [attempted, setAttempted] = useState(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  useEffect(() => { onLock(busy || (upload !== null && !accepted)); }, [busy, upload, accepted, onLock]);
  useEffect(() => {
    if (!attempted || accepted) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [attempted, accepted]);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!file && !upload) return;
    setBusy(true); setError(null);
    try {
      const prepared = upload ?? await prepareUpload(file!, scope, source, key);
      if (!mounted.current) return;
      setUpload(prepared); setAttempted(true);
      const result = await submitUpload(prepared);
      if (mounted.current) { setAccepted(result); onAccepted(result.job); }
    } catch (failure) { if (mounted.current) setError(failure); }
    finally { if (mounted.current) setBusy(false); }
  }
  function reset() { setFile(null); setUpload(null); setKey(crypto.randomUUID()); setError(null); setAccepted(null); setAttempted(false); setFileKey(value => value + 1); setResetting(false); }
  return <section className="panel upload-panel"><div className="section-heading"><h2><FileUp size={17} />Submit a merchant feed</h2><span className="mono">NDJSON / 1 MiB MAX</span></div><p className="caption">Up to 1,000 rows · 4 KiB per line · UTF-8 · Full versioned offer snapshots. The API validates every row.</p>
    <form onSubmit={submit}><div className="upload-fields"><label>Feed file<input key={fileKey} type="file" accept=".ndjson,.jsonl,application/x-ndjson" required disabled={busy || !!upload} onChange={e => setFile(e.target.files?.[0] ?? null)} /></label><label>Source label<input required pattern={identifier} maxLength={64} value={source} disabled={busy || !!upload} onChange={e => setSource(e.target.value)} /></label><label>Idempotency key<input className="mono" required pattern={identifier} maxLength={64} value={key} disabled={busy || !!upload} onChange={e => setKey(e.target.value)} /></label></div><div className="actions"><button className="primary" disabled={busy || !!accepted || (!file && !upload)}>{busy ? 'Submitting…' : upload ? 'Retry identical upload' : 'Submit feed'}</button>{(upload || accepted) && <button type="button" disabled={busy} onClick={() => accepted ? reset() : setResetting(true)}>Start another upload</button>}<span className="caption">Exact-byte checksum calculated before upload.</span></div></form>
    {upload && <details className="provenance"><summary>Request fingerprint</summary><dl><dt>SHA-256 · exact bytes</dt><dd className="mono">{upload.sha256}</dd><dt>File / size / scope</dt><dd>{upload.name} · {upload.bytes.byteLength} bytes · {upload.scope.tenant} / {upload.scope.merchant}</dd><dt>Retry identity</dt><dd className="mono">{upload.source} / {upload.key}</dd></dl></details>}
    {error != null && <ErrorNotice>{message(error)}{attempted && <p>The outcome may be unknown. Retry the identical upload to resolve the same job; do not change its key. Keep this tab open until resolved.</p>}</ErrorNotice>}
    {accepted && <div className="notice" role="status"><div><strong>{accepted.created ? 'Feed admitted.' : 'Existing job recovered.'}</strong> Inspect the job below for processing and row outcomes. Admission is not successful completion.</div></div>}
    {resetting && <Modal title="Leave this upload unresolved?" onClose={() => setResetting(false)}><p>The API may already have admitted this feed. A new key can create another job. Keep the request fingerprint if you need to reconcile the original request.</p><div className="actions end"><button onClick={() => setResetting(false)}>Keep current upload</button><button className="danger" onClick={reset}>Discard local attempt</button></div></Modal>}
  </section>;
}

const reasons: Record<string, string> = {
  INVALID_JSON: 'Invalid JSON object.', INVALID_OFFER: 'Invalid or incomplete offer fields.', SCOPE_MISMATCH: 'Row does not match the upload scope.', FUTURE_SOURCE: 'Source timestamp was in the future at admission.', VERSION_CONFLICT: 'Older version or conflicting same-version facts. Submit a corrected higher version.', CANCELLED: 'Pending row cancelled by an operator.',
};
function JobDetail({ scope, id, onBack }: { scope: Scope; id: string; onBack: () => void }) {
  const [job, setJob] = useState<Job | null>(null);
  const [rows, setRows] = useState<Rows | null>(null);
  const [actions, setActions] = useState<Action[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [fresh, setFresh] = useState(false);
  const [loadedAt, setLoadedAt] = useState<string | null>(null);
  const [control, setControl] = useState<'retry' | 'cancel' | null>(null);
  const [reason, setReason] = useState('');
  const [mutating, setMutating] = useState(false);
  const [controlError, setControlError] = useState<unknown>(null);
  const epoch = useRef(0);
  const base = feedUrl(scope, id);
  async function refresh() {
    const token = ++epoch.current; setBusy(true); setError(null); setFresh(false);
    try {
      const [next, receipts, audit] = await Promise.all([request<Job>(base), request<Rows>(`${base}/rows?limit=100&after=0`), request<Action[]>(`${base}/actions`)]);
      if (token === epoch.current) { setJob(next); setRows(receipts); setActions(audit); setFresh(true); setLoadedAt(new Date().toISOString()); }
    } catch (failure) { if (token === epoch.current) setError(failure); }
    finally { if (token === epoch.current) setBusy(false); }
  }
  useEffect(() => { void refresh(); return () => { epoch.current++; }; }, [base]);
  async function moreRows() {
    if (rows?.nextAfter == null) return;
    const token = ++epoch.current; setBusy(true); setError(null);
    try { const next = await request<Rows>(`${base}/rows?limit=100&after=${rows.nextAfter}`); if (token === epoch.current) setRows(previous => ({ rows: [...(previous?.rows ?? []), ...next.rows], nextAfter: next.nextAfter })); }
    catch (failure) { if (token === epoch.current) setError(failure); }
    finally { if (token === epoch.current) setBusy(false); }
  }
  async function act(event: FormEvent) {
    event.preventDefault(); if (!control || !fresh) return;
    setMutating(true); setControlError(null); setFresh(false);
    try { await request<Job>(`${base}/${control}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }) }); setControl(null); await refresh(); }
    catch (failure) { setControlError(failure); }
    finally { setMutating(false); }
  }
  return <section className="workspace-section" aria-busy={busy}><button className="text-button" disabled={mutating} onClick={onBack}><ArrowLeft size={14} />Back to loaded jobs</button><div className="section-heading job-heading"><h2>Job investigation</h2><button disabled={busy || mutating} onClick={() => void refresh()}><RefreshCw size={14} />Refresh job</button></div><p className="mono">{id}</p>
    {error != null && <ErrorNotice>{message(error)} Previous evidence, if shown, has not been refreshed. Actions are disabled until a successful refresh.</ErrorNotice>}
    {!job && busy && <p role="status">Loading job, row receipts and action history…</p>}
    {job && <>
      <div className="job-summary panel"><div className="section-heading"><h2>{job.source}</h2><Status value={job.state} /></div><p className="caption">Snapshot loaded <Time value={loadedAt} />. Refresh manually; no background polling.</p><label className="progress-label">Rows resolved: {job.processed} / {job.total}<progress max={job.total || 1} value={job.processed} /></label><dl className="counters">{[['Applied', job.applied], ['Replayed', job.replayed], ['Rejected', job.rejected], ['Cancelled', job.cancelled], ['Pending', job.total - job.processed]].map(([label, count]) => <div key={label}><dt>{label}</dt><dd>{count}</dd></div>)}</dl>
      <p className="caption">Applied rows are committed to source storage and the outbox. Search visibility is asynchronous. Old source timestamps remain old.</p>
      {job.state === 'QUEUED' && <p className="notice">Waiting for a worker or retry delay. Accepting a feed does not enable the worker.</p>}
      {job.state === 'PAUSED' && <p className="notice">Processing paused after repeated database failures. Fix the dependency, then resume pending rows. Rejected rows are not retried.</p>}
      {job.state === 'CANCELLED' && <p className="notice">Only pending rows were cancelled. Previously committed offers and outbox events remain.</p>}
      {job.state === 'COMPLETED_WITH_ERRORS' && <p className="notice">All rows resolved, with rejections. Correct row errors in a new feed; use higher source versions when required.</p>}
      {!terminal(job.state) && <div className="actions">{job.state === 'PAUSED' && <button disabled={!fresh || busy || mutating} onClick={() => { setReason(''); setControlError(null); setControl('retry'); }}>Resume pending rows</button>}<button className="danger" disabled={!fresh || busy || mutating} onClick={() => { setReason(''); setControlError(null); setControl('cancel'); }}>Cancel pending rows</button>{!fresh && <span className="caption">Refresh job before another action.</span>}</div>}
      <details className="provenance"><summary>Immutable provenance & worker evidence</summary><dl><dt>Scope</dt><dd className="mono">{job.tenantId} / {job.merchantId}</dd><dt>SHA-256 / bytes</dt><dd className="mono">{job.sha256} / {job.bytes}</dd><dt>Created</dt><dd><Time value={job.createdAt} /></dd><dt>Available after / lease until</dt><dd><Time value={job.availableAt} /> / <Time value={job.leaseUntil} /></dd><dt>Consecutive failures / last error</dt><dd>{job.failures} / {job.lastError ?? 'None reported'}</dd></dl></details></div>
      <div className="section-heading workspace-section"><h2>Row receipts <span className="muted">/ {rows?.rows.length ?? 0} loaded</span></h2></div><p className="caption">Stable input line numbers. Raw feed bodies and invalid payloads are never returned.</p>
      <div className="table-wrap"><table><caption className="sr-only">Durable row receipts for this job</caption><thead><tr><th>Line</th><th>Offer / version</th><th>Outcome</th><th>Reason / source time</th><th>Evidence</th></tr></thead><tbody>{rows?.rows.map(row => <tr key={row.number}><td className="mono">{row.number}</td><td className="mono">{row.offerId ?? 'No valid identity'}<small>{row.version != null ? `v${row.version}` : '—'}</small></td><td><Status value={row.state} /></td><td>{row.reason ? <><strong className="reason-code">{row.reason}</strong><small>{reasons[row.reason] ?? 'Unknown server code; inspect backend documentation.'}</small></> : '—'}<small><Time value={row.sourceUpdatedAt} /></small></td><td><details><summary>Receipt</summary><div className="receipt"><p className="mono">SHA-256<br />{row.sha256}</p><p>Finished <Time value={row.finishedAt} /></p></div></details></td></tr>)}</tbody></table></div>
      {rows?.nextAfter != null && <div className="pagination"><p>Receipts continue after line {rows.nextAfter}.</p><button disabled={busy} onClick={() => void moreRows()}>Load more rows</button></div>}
      <section className="workspace-section"><h2>Local operator actions</h2><p className="caption">Reason identifiers and database timestamps; not an authenticated-actor audit.</p>{actions.length ? <ol className="audit">{actions.map(action => <li key={action.id}><Status value={action.action} /><code>{action.reason}</code><Time value={action.createdAt} /></li>)}</ol> : <p className="caption">No retained actions.</p>}</section>
    </>}
    {control && job && <Modal title={control === 'cancel' ? 'Cancel remaining rows?' : 'Resume pending rows?'} busy={mutating} onClose={() => setControl(null)}><form onSubmit={act}><p>{control === 'cancel' ? `The last loaded snapshot has ${job.total - job.processed} pending rows. Cancellation does not undo the ${job.applied} applied rows or their outbox events. More rows may commit before cancellation takes effect.` : 'This resumes only pending rows after the dependency has been repaired. Rejected rows and committed rows are not replayed.'}</p><label>Reason identifier<input autoFocus required maxLength={64} pattern={identifier} placeholder="e.g. dependency-restored" value={reason} disabled={mutating || !fresh} onChange={e => setReason(e.target.value)} /></label><p className="caption">1–64 letters, numbers, dots, underscores or hyphens; start with a letter or number.</p>{controlError != null && <ErrorNotice>{message(controlError)} The action may have succeeded. Close this dialog and refresh the job before any further action.</ErrorNotice>}<div className="actions end"><button type="button" disabled={mutating} onClick={() => setControl(null)}>Go back</button><button className={control === 'cancel' ? 'danger' : 'primary'} disabled={mutating || !fresh}>{mutating ? 'Applying…' : control === 'cancel' ? 'Confirm cancellation' : 'Confirm resume'}</button></div></form></Modal>}
  </section>;
}
