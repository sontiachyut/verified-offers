import { parse } from 'lossless-json';

// Java long values exceed JavaScript's exact-number range. Preserve them as strings.
export type Integer = number | string;
export interface Offer { tenantId: string; merchantId: string; offerId: string; version: Integer; title: string; priceMinor: Integer; currency: string; availableQuantity: number; sourceUpdatedAt: string; deleted: boolean }
export interface Evidence { offer: Offer; indexVersion: Integer; indexSourceUpdatedAt: string; verification: { outcome: string; sourceVersion: Integer; sourceUpdatedAt: string; verifiedAt: string } }
export interface SearchPage { results: Evidence[]; nextCursor: string | null; expiresAt: string; scanLimitReached: boolean }
export interface SearchScope { tenantId: string; q: string; limit: number }
export interface Scope { tenant: string; merchant: string }
export interface Job { id: string; tenantId: string; merchantId: string; source: string; sha256: string; bytes: number; total: number; processed: number; applied: number; replayed: number; rejected: number; cancelled: number; state: string; failures: number; lastError: string | null; availableAt: string; leaseUntil: string | null; createdAt: string }
export interface Receipt { number: number; sha256: string; offerId: string | null; version: Integer | null; sourceUpdatedAt: string | null; state: string; reason: string | null; finishedAt: string | null }
export interface Action { id: Integer; action: string; reason: string; createdAt: string }
export interface Jobs { jobs: Job[]; nextAfter: string | null }
export interface Rows { rows: Receipt[]; nextAfter: number | null }
export const identifier = '[A-Za-z0-9][A-Za-z0-9._-]{0,63}';
export const terminal = (state: string) => ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED'].includes(state);
export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
export function decode(text: string): unknown {
  return parse(text, undefined, value => {
    const number = Number(value);
    return Number.isSafeInteger(number) ? number : value;
  });
}
export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const timeout = AbortSignal.timeout(20_000);
  try {
    const response = await fetch(url, { ...init, cache: 'no-store', signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout });
    if (!response.ok) throw new ApiError(response.status, errorMessage(response.status));
    return decode(await response.text()) as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (init.signal?.aborted) throw error;
    throw new ApiError(0, timeout.aborted ? 'The request timed out. Its outcome may be unknown.' : 'Cannot reach the local API. Check that the backend is running and this feature is enabled.');
  }
}
export function errorMessage(status: number) {
  return ({ 400: 'The request was rejected. Check scope, input limits and request values.', 404: 'Not found in this scope, or this API feature is disabled.', 409: 'The request conflicts with the current state. Refresh before trying a different action.', 410: 'This search snapshot has expired or closed. Start a new search to continue.', 413: 'This feed exceeds the 1 MiB upload limit.', 415: 'Only uncompressed UTF-8 NDJSON is supported.', 429: 'The API has reached a bounded capacity limit. Wait before retrying.', 503: 'A backend dependency is unavailable. No unverified fallback results are shown.' } as Record<number, string>)[status] ?? `The API returned HTTP ${status}. Try again after checking the local service.`;
}
export const message = (error: unknown) => error instanceof Error ? error.message : 'The request could not be completed.';
export function searchUrl(scope: SearchScope, cursor?: string | null) {
  const params = new URLSearchParams({ tenantId: scope.tenantId, q: scope.q, limit: String(scope.limit) });
  if (cursor) params.set('cursor', cursor);
  return `/api/v1/search?${params}`;
}
export async function closeSearch(scope: SearchScope, cursor?: string | null) {
  if (cursor) await request(searchUrl(scope, cursor), { method: 'DELETE', keepalive: true }).catch(() => undefined);
}
export const feedUrl = (scope: Scope, id?: string) => `/api/v1/feeds/${encodeURIComponent(scope.tenant)}/${encodeURIComponent(scope.merchant)}${id ? `/${encodeURIComponent(id)}` : ''}`;
export function money(cents: Integer) {
  const value = BigInt(cents);
  return `$${(value / 100n).toLocaleString('en-US')}.${(value % 100n).toString().padStart(2, '0')}`;
}
export const timestamp = (value: string | null) => value ? new Date(value).toISOString().replace('T', ' ').replace('.000Z', ' UTC').replace('Z', ' UTC') : '—';
export interface Upload { scope: Scope; source: string; key: string; sha256: string; bytes: ArrayBuffer; name: string }
export async function prepareUpload(file: File, scope: Scope, source: string, key: string): Promise<Upload> {
  if (file.size === 0 || file.size > 1_048_576) throw new Error('Select a non-empty NDJSON file no larger than 1 MiB.');
  const bytes = await file.arrayBuffer();
  const sha256 = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(value => value.toString(16).padStart(2, '0')).join('');
  return { scope: { ...scope }, source, key, sha256, bytes, name: file.name };
}
export const submitUpload = (upload: Upload) => request<{ created: boolean; job: Job }>(feedUrl(upload.scope), { method: 'POST', headers: { 'Content-Type': 'application/x-ndjson', 'Idempotency-Key': upload.key, 'X-Content-SHA256': upload.sha256, 'X-Feed-Source': upload.source }, body: upload.bytes });
