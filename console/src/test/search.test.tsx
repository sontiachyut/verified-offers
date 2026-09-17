import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SearchWorkspace } from '../SearchWorkspace';
import { decode, money } from '../api';

const page = (extra = {}) => ({ results: [], nextCursor: null, expiresAt: new Date(Date.now() + 120_000).toISOString(), scanLimitReached: false, ...extra });
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
const result = { offer: { tenantId: 'demo', merchantId: 'synthetic', offerId: 'keyboard', title: 'Mechanical keyboard', priceMinor: 9900, currency: 'USD', version: 2, availableQuantity: 8, sourceUpdatedAt: '2026-09-16T12:00:00Z' }, indexVersion: 2, indexSourceUpdatedAt: '2026-09-16T12:00:00Z', verification: { outcome: 'VERIFIED', sourceVersion: 2, sourceUpdatedAt: '2026-09-16T12:00:00Z', verifiedAt: '2026-09-16T12:00:01Z' } };
async function search() { const user = userEvent.setup(); await user.type(screen.getByLabelText('Search catalog'), 'keyboard'); await user.click(screen.getByRole('button', { name: 'Search offers' })); return user; }
describe('verified search', () => {
  it('starts without invented results or automatic requests', () => { const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); render(<SearchWorkspace />); expect(screen.getByText('Start with an offer.')).toBeInTheDocument(); expect(fetch).not.toHaveBeenCalled(); });
  it('continues an empty page using the frozen scope despite edited inputs', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(reply(page({ nextCursor: 'opaque' }))).mockResolvedValueOnce(reply(page({ results: [result] })));
    vi.stubGlobal('fetch', fetch); render(<SearchWorkspace />); const user = await search();
    await screen.findByText('No verified offers on this page.');
    await user.clear(screen.getByLabelText('Tenant')); await user.type(screen.getByLabelText('Tenant'), 'other');
    await user.click(screen.getByRole('button', { name: 'Continue search' }));
    await screen.findByText('Mechanical keyboard'); expect(fetch.mock.calls[1][0]).toBe('/api/v1/search?tenantId=demo&q=keyboard&limit=10&cursor=opaque');
    await user.click(screen.getByRole('button', { name: /Inspect Mechanical/ })); expect(screen.getByRole('region', { name: 'Selected offer evidence' })).toHaveTextContent('2026-09-16 12:00:01 UTC'); expect(screen.getByText('$99.00')).toBeInTheDocument();
  });
  it('preserves cursor after 503 and never auto-restarts after 410', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(reply(page({ nextCursor: 'same' }))).mockResolvedValueOnce(reply({}, 503)).mockResolvedValueOnce(reply({}, 410));
    vi.stubGlobal('fetch', fetch); render(<SearchWorkspace />); const user = await search(); await user.click(await screen.findByRole('button', { name: 'Continue search' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('dependency'); await user.click(screen.getByRole('button', { name: 'Retry request' })); await screen.findByRole('button', { name: 'Start new search' }); expect(fetch).toHaveBeenCalledTimes(3); expect(fetch.mock.calls[1][0]).toBe(fetch.mock.calls[2][0]);
  });
  it('closes an unfinished search when leaving the view', async () => { const fetch = vi.fn().mockResolvedValue(reply(page({ nextCursor: 'release-me' }))); vi.stubGlobal('fetch', fetch); const view = render(<SearchWorkspace />); await search(); await screen.findByRole('button', { name: 'Continue search' }); view.unmount(); await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2)); expect(fetch.mock.calls[1][1].method).toBe('DELETE'); });
  it('closes a late page arriving after unmount', async () => { let resolve!: (r: Response) => void; const fetch = vi.fn().mockImplementationOnce(() => new Promise<Response>(r => { resolve = r; })).mockResolvedValue(reply({ closed: true })); vi.stubGlobal('fetch', fetch); const view = render(<SearchWorkspace />); await search(); view.unmount(); resolve(reply(page({ nextCursor: 'late' }))); await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2)); expect(fetch.mock.calls[1][0]).toContain('cursor=late'); });
  it('exposes loading state and scan bound without claiming more results', async () => { let resolve!: (r: Response) => void; vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(r => { resolve = r; }))); render(<SearchWorkspace />); await search(); expect(screen.getByRole('button', { name: 'Searching…' })).toBeDisabled(); resolve(reply(page({ scanLimitReached: true }))); expect(await screen.findByText(/10,000-candidate scan bound/)).toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Continue search' })).not.toBeInTheDocument(); });
  it('expires locally without polling or claiming that existing evidence is current', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(page({ nextCursor: 'old', expiresAt: '2020-01-01T00:00:00Z' })))); render(<SearchWorkspace />); await search(); expect(await screen.findByRole('button', { name: 'Start new search' })).toBeInTheDocument(); });
  it('supports keyboard form submission', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(page()))); render(<SearchWorkspace />); const user = userEvent.setup(); const input = screen.getByLabelText('Search catalog'); input.focus(); await user.type(input, 'keyboard{Enter}'); expect(await screen.findByText('End of this search snapshot.')).toBeInTheDocument(); });
  it('keeps untrusted text inert', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(page({ results: [{ ...result, offer: { ...result.offer, title: '<script>bad()</script>' } }] })))); render(<SearchWorkspace />); await search(); await screen.findByText('<script>bad()</script>'); expect(document.querySelector('script')).toBeNull(); });
});
describe('integer evidence', () => {
  it('does not round long versions or prices', () => { const data = decode('{"version":9223372036854775807,"priceMinor":9223372036854775807,"count":25}') as Record<string, number | string>; expect(data.version).toBe('9223372036854775807'); expect(data.count).toBe(25); expect(money(data.priceMinor)).toBe('$92,233,720,368,547,758.07'); });
});
