import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowRight, Search, ShieldCheck } from 'lucide-react';
import {
  ApiError,
  closeSearch,
  identifier,
  message,
  money,
  request,
  searchUrl,
  type Evidence,
  type SearchPage,
  type SearchScope,
} from './api';
import { ErrorNotice, Time } from './ui';

export function SearchWorkspace() {
  const [tenant, setTenant] = useState('demo');
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(10);
  const [scope, setScope] = useState<SearchScope | null>(null);
  const [page, setPage] = useState<SearchPage | null>(null);
  const [rows, setRows] = useState<Evidence[]>([]);
  const [selected, setSelected] = useState<Evidence | null>(null);
  const evidence = useRef<HTMLElement>(null);
  const evidenceTrigger = useRef<HTMLButtonElement | null>(null);
  useEffect(() => {
    if (selected) evidence.current?.focus();
  }, [selected]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [expired, setExpired] = useState(false);
  const session = useRef<{
    scope: SearchScope;
    cursor: string | null;
  } | null>(null);
  const epoch = useRef(0);
  useEffect(
    () => () => {
      epoch.current++;
      if (session.current) void closeSearch(session.current.scope, session.current.cursor);
    },
    [],
  );
  useEffect(() => {
    if (!page?.nextCursor) return;
    const remaining = new Date(page.expiresAt).getTime() - Date.now();
    const timer = window.setTimeout(() => setExpired(true), Math.max(0, remaining));
    return () => clearTimeout(timer);
  }, [page]);
  async function load(nextScope: SearchScope, cursor?: string | null) {
    const token = ++epoch.current;
    setBusy(true);
    setError(null);
    if (!cursor) {
      if (session.current) void closeSearch(session.current.scope, session.current.cursor);
      session.current = null;
      setScope(nextScope);
      setPage(null);
      setRows([]);
      setSelected(null);
      setExpired(false);
    }
    try {
      const result = await request<SearchPage>(searchUrl(nextScope, cursor));
      if (token !== epoch.current) {
        void closeSearch(nextScope, result.nextCursor);
        return;
      }
      session.current = { scope: nextScope, cursor: result.nextCursor };
      setPage(result);
      setRows((previous) => (cursor ? [...previous, ...result.results] : result.results));
    } catch (failure) {
      if (token === epoch.current) {
        setError(failure);
        if (failure instanceof ApiError && failure.status === 410) setExpired(true);
      }
    } finally {
      if (token === epoch.current) setBusy(false);
    }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    if (query.trim()) void load({ tenantId: tenant, q: query.trim(), limit });
  }
  return (
    <>
      <section className="panel">
        <form onSubmit={submit} className="search-form">
          <label>
            Tenant
            <input
              required
              pattern={identifier}
              maxLength={64}
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
            />
          </label>
          <label className="grow">
            Search catalog
            <input
              required
              maxLength={200}
              placeholder="Try keyboard, monitor, or headphones"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </label>
          <label>
            Page size
            <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {[5, 10, 25, 50].map((n) => (
                <option key={n}>{n}</option>
              ))}
            </select>
          </label>
          <button className="primary" disabled={busy || !query.trim()}>
            <Search size={16} />
            {busy ? 'Searching…' : 'Search offers'}
          </button>
        </form>
      </section>
      {error != null && (
        <ErrorNotice>
          {message(error)}
          {!expired && scope && (
            <button disabled={busy} onClick={() => void load(scope, page?.nextCursor)}>
              Retry request
            </button>
          )}
        </ErrorNotice>
      )}
      <div role="status" className="sr-only">
        {busy
          ? 'Searching source-verified offers'
          : page
            ? `${rows.length} results loaded. ${page.nextCursor ? 'More candidates available.' : 'Search complete.'}`
            : ''}
      </div>
      {!scope && (
        <section className="empty">
          <Search size={30} aria-hidden="true" />
          <h2>Start with an offer.</h2>
          <p>
            Search returns only candidates that pass the server’s current source check.
            <br />
            Open a result to inspect its versions and timestamps.
          </p>
          <span className="eyebrow">INDEX CANDIDATE → SOURCE CHECK → AS-OF EVIDENCE</span>
        </section>
      )}
      {scope && (
        <section className="results" aria-busy={busy}>
          <div className="section-heading">
            <h2>
              {rows.length} results loaded <span className="muted">/ {scope.q}</span>
            </h2>
            <span className="mono">tenant: {scope.tenantId}</span>
          </div>
          <p className="caption">
            Verified when returned, not a live stock or checkout guarantee. No exact total is
            available.
          </p>
          {page && rows.length === 0 && (
            <div className="empty compact">
              <h2>No verified offers on this page.</h2>
              <p>
                {page.nextCursor
                  ? 'The examined candidates did not pass verification. Continue to examine the next batch.'
                  : 'No eligible matches were returned. Facts may be stale, changed, unavailable or not yet indexed.'}
              </p>
            </div>
          )}
          {rows.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Loaded verified offers with source evidence</caption>
                <thead>
                  <tr>
                    <th>Offer / merchant</th>
                    <th className="numeric">Price · USD</th>
                    <th className="numeric">Stock at check</th>
                    <th>Source version</th>
                    <th>
                      <span className="sr-only">Evidence</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, index) => (
                    <tr
                      key={`${row.offer.merchantId}/${row.offer.offerId}/${index}`}
                      className={selected === row ? 'selected' : ''}
                    >
                      <td>
                        <strong>{row.offer.title}</strong>
                        <small className="mono">
                          {row.offer.merchantId} / {row.offer.offerId}
                        </small>
                      </td>
                      <td className="numeric money">{money(row.offer.priceMinor)}</td>
                      <td className="numeric mono">{row.offer.availableQuantity}</td>
                      <td className="mono">v{row.offer.version}</td>
                      <td>
                        <button
                          className="text-button"
                          aria-label={`Inspect ${row.offer.title} from ${row.offer.merchantId}`}
                          onClick={(event) => {
                            evidenceTrigger.current = event.currentTarget;
                            setSelected(selected === row ? null : row);
                          }}
                        >
                          Inspect <ArrowRight size={14} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {selected && (
            <section
              className="evidence panel"
              aria-label="Selected offer evidence"
              ref={evidence}
              tabIndex={-1}
            >
              <div className="section-heading">
                <h2>
                  <ShieldCheck size={18} />
                  {selected.offer.title}
                </h2>
                <button
                  onClick={() => {
                    setSelected(null);
                    evidenceTrigger.current?.focus();
                  }}
                >
                  Close evidence
                </button>
              </div>
              <p className="caption">
                Server outcome: <strong>{selected.verification.outcome}</strong> · This evidence is
                historical as of the check below.
              </p>
              <dl className="facts">
                <div>
                  <dt>Authoritative source</dt>
                  <dd>v{selected.verification.sourceVersion}</dd>
                  <dd>
                    <Time value={selected.verification.sourceUpdatedAt} />
                  </dd>
                </div>
                <div>
                  <dt>Search projection</dt>
                  <dd>v{selected.indexVersion}</dd>
                  <dd>
                    <Time value={selected.indexSourceUpdatedAt} />
                  </dd>
                </div>
                <div>
                  <dt>Verified at</dt>
                  <dd>
                    <Time value={selected.verification.verifiedAt} />
                  </dd>
                  <dd>Five-minute source freshness policy</dd>
                </div>
              </dl>
              <p className="caption mono">
                {selected.offer.tenantId} / {selected.offer.merchantId} / {selected.offer.offerId}
              </p>
            </section>
          )}
          {page && (
            <div className="pagination">
              <div>
                <p>
                  {expired
                    ? 'Snapshot expired. Start a new search.'
                    : page.scanLimitReached
                      ? 'The 10,000-candidate scan bound was reached. Refine your search.'
                      : page.nextCursor
                        ? 'More candidates can be examined.'
                        : 'End of this search snapshot.'}
                </p>
                {page.nextCursor && (
                  <span>
                    Fixed expiry: <Time value={page.expiresAt} />
                  </span>
                )}
              </div>
              {page.nextCursor && !expired && (
                <button disabled={busy} onClick={() => void load(scope, page.nextCursor)}>
                  Continue search <ArrowRight size={15} />
                </button>
              )}
              {expired && (
                <button disabled={busy} onClick={() => void load(scope)}>
                  Start new search
                </button>
              )}
            </div>
          )}
        </section>
      )}
    </>
  );
}
