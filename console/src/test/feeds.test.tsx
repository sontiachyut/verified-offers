import { webcrypto } from 'node:crypto';
import { StrictMode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { FeedWorkspace } from '../FeedWorkspace';
import { App } from '../App';
import { ApiError, errorMessage, prepareUpload, request, submitUpload, type Job } from '../api';

const id = '10000000-0000-4000-8000-000000000001';
const second = '20000000-0000-4000-8000-000000000002';
const now = '2026-09-16T12:00:00Z';
const job: Job = {
  id,
  tenantId: 'demo',
  merchantId: 'synthetic',
  source: 'synthetic-fixture',
  sha256: 'a'.repeat(64),
  bytes: 500,
  total: 4,
  processed: 2,
  applied: 1,
  replayed: 0,
  rejected: 1,
  cancelled: 0,
  state: 'PAUSED',
  failures: 5,
  lastError: 'DATABASE_ERROR',
  availableAt: now,
  leaseUntil: null,
  createdAt: now,
};
const receipt = {
  number: 1,
  sha256: 'b'.repeat(64),
  offerId: null,
  version: null,
  sourceUpdatedAt: null,
  state: 'REJECTED',
  reason: 'INVALID_JSON',
  finishedAt: now,
};
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
function fixtureFetch(
  extra?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined,
) {
  const mock = vi.fn((input: string, init?: RequestInit) => {
    const override = extra?.(input, init);
    if (override) return Promise.resolve(override);
    if (input.includes('/rows'))
      return Promise.resolve(reply({ rows: [receipt], nextAfter: null }));
    if (input.endsWith('/actions')) return Promise.resolve(reply([]));
    if (input.includes(`/${id}`)) return Promise.resolve(reply(job));
    return Promise.resolve(reply({ jobs: [job], nextAfter: null }));
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}
async function openJob() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
  await user.click(await screen.findByRole('button', { name: `Inspect job ${id}` }));
  await screen.findByRole('button', { name: 'Resume pending rows' });
  return user;
}
function file(bytes = '{"synthetic":true}\r\n') {
  const blob = new File([bytes], 'synthetic.ndjson', {
    type: 'application/x-ndjson',
  });
  Object.defineProperty(blob, 'arrayBuffer', {
    value: async () => new TextEncoder().encode(bytes).buffer,
  });
  return blob;
}
function submitFeed() {
  // user-event supplies a FileList facade; jsdom 30's native file-required check
  // reads its internal FileList instead. Assert the selected file and every other
  // native constraint, then dispatch submit. No application validation is removed.
  expect((screen.getByLabelText('Feed file') as HTMLInputElement).files).toHaveLength(1);
  const form = screen.getByRole('button', { name: 'Submit feed' }).closest('form')!;
  for (const input of form.querySelectorAll('input:not([type=file])'))
    expect((input as HTMLInputElement).checkValidity()).toBe(true);
  fireEvent.submit(form);
}
describe('feed investigation', () => {
  it('does not request data before an explicit scope is opened', () => {
    const fetch = fixtureFetch();
    render(<FeedWorkspace />);
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Open feed scope' })).toBeEnabled();
  });
  it('shows loading, empty and failed job-list states', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(reply({ jobs: [], nextAfter: null }))
      .mockResolvedValueOnce(reply({}, 503));
    vi.stubGlobal('fetch', fetch);
    render(<FeedWorkspace />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    expect(await screen.findByText('No jobs in this scope.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh list' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('dependency');
  });
  it('lists more jobs by returned UUID cursor, not a made-up offset', async () => {
    const fetch = fixtureFetch((url) =>
      url.endsWith('limit=20')
        ? reply({ jobs: [job], nextAfter: id })
        : url.includes(`after=${id}`)
          ? reply({ jobs: [{ ...job, id: second }], nextAfter: null })
          : undefined,
    );
    render(<FeedWorkspace />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    await user.click(await screen.findByRole('button', { name: 'Load more jobs' }));
    expect(
      await screen.findByRole('button', {
        name: `Inspect job ${second}`,
      }),
    ).toBeInTheDocument();
    expect(fetch.mock.calls[1][0]).toContain(`after=${id}`);
  });
  it('shows sanitized row evidence, provenance and separately labeled progress', async () => {
    fixtureFetch();
    render(<FeedWorkspace />);
    await openJob();
    expect(screen.getByText('INVALID_JSON')).toBeInTheDocument();
    expect(screen.getByText('Invalid JSON object.')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '2');
    expect(screen.getByText(/Applied rows are committed/)).toBeInTheDocument();
    expect(screen.getByText(/not an authenticated-actor audit/)).toBeInTheDocument();
  });
  it('requires explicit confirmation and sends only a bounded reason for cancellation', async () => {
    let cancelled = false;
    const fetch = fixtureFetch((url, init) => {
      if (url.endsWith('/cancel')) {
        cancelled = true;
        return reply({ ...job, state: 'CANCELLED' });
      }
      if (cancelled && url.endsWith(id))
        return reply({
          ...job,
          state: 'CANCELLED',
          processed: 4,
          cancelled: 2,
        });
    });
    render(<FeedWorkspace />);
    const user = await openJob();
    await user.click(screen.getByRole('button', { name: 'Cancel pending rows' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('does not undo the 1 applied rows');
    expect(fetch.mock.calls.some((call) => call[1]?.method === 'POST')).toBe(false);
    await user.type(within(dialog).getByLabelText('Reason identifier'), 'operator-review');
    await user.click(
      within(dialog).getByRole('button', {
        name: 'Confirm cancellation',
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(fetch.mock.calls.find((call) => call[0].endsWith('/cancel'))?.[1]?.body).toBe(
      '{"reason":"operator-review"}',
    );
    expect(screen.queryByRole('button', { name: 'Cancel pending rows' })).not.toBeInTheDocument();
  });
  it('locks further mutations after an uncertain response until refresh', async () => {
    fixtureFetch((url) =>
      url.endsWith('/retry') ? Promise.reject(new TypeError('network lost')) : undefined,
    );
    render(<FeedWorkspace />);
    const user = await openJob();
    await user.click(screen.getByRole('button', { name: 'Resume pending rows' }));
    await user.type(screen.getByLabelText('Reason identifier'), 'database-restored');
    await user.click(screen.getByRole('button', { name: 'Confirm resume' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('action may have succeeded');
    expect(screen.getByRole('button', { name: 'Confirm resume' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Go back' }));
    expect(screen.getByRole('button', { name: 'Resume pending rows' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Refresh job' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Resume pending rows' })).toBeEnabled(),
    );
  });
  it('disables actions when refreshed evidence cannot be loaded', async () => {
    let fail = false;
    fixtureFetch((url) => (fail && url.endsWith(id) ? reply({}, 503) : undefined));
    render(<FeedWorkspace />);
    const user = await openJob();
    fail = true;
    await user.click(screen.getByRole('button', { name: 'Refresh job' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Resume pending rows' })).toBeDisabled();
  });
  it('continues row receipts using nextAfter and does not poll', async () => {
    const fetch = fixtureFetch((url) =>
      url.includes('after=0')
        ? reply({ rows: [receipt], nextAfter: 1 })
        : url.includes('after=1')
          ? reply({
              rows: [{ ...receipt, number: 2 }],
              nextAfter: null,
            })
          : undefined,
    );
    render(<FeedWorkspace />);
    const user = await openJob();
    await user.click(screen.getByRole('button', { name: 'Load more rows' }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Load more rows' })).not.toBeInTheDocument(),
    );
    expect(fetch.mock.calls.at(-1)?.[0]).toContain('after=1');
    expect(screen.getAllByText('INVALID_JSON')).toHaveLength(2);
  });
  it('returns focus to the initiating control when a confirmation is dismissed', async () => {
    fixtureFetch();
    render(<FeedWorkspace />);
    const user = await openJob();
    const button = screen.getByRole('button', {
      name: 'Cancel pending rows',
    });
    await user.click(button);
    await user.click(screen.getByRole('button', { name: 'Go back' }));
    expect(button).toHaveFocus();
  });
  it('does not leak a late job-list response into a different view after unmount', async () => {
    let resolve!: (r: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((r) => {
            resolve = r;
          }),
      ),
    );
    const view = render(<FeedWorkspace />);
    await userEvent.click(screen.getByRole('button', { name: 'Open feed scope' }));
    view.unmount();
    resolve(reply({ jobs: [job], nextAfter: null }));
    expect(screen.queryByText(job.source)).not.toBeInTheDocument();
  });
});
describe('feed admission', () => {
  it('can submit after a development effect cleanup and remount', async () => {
    vi.stubGlobal('crypto', webcrypto);
    fixtureFetch((_url, init) =>
      init?.method === 'POST' ? reply({ created: true, job }) : undefined,
    );
    render(
      <StrictMode>
        <FeedWorkspace />
      </StrictMode>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    await user.upload(await screen.findByLabelText('Feed file'), file());
    submitFeed();
    expect(await screen.findByText('Feed admitted.')).toBeInTheDocument();
  });
  it('hashes and sends exact bytes including CRLF, preserving a retry identity', async () => {
    vi.stubGlobal('crypto', webcrypto);
    const input = file();
    const prepared = await prepareUpload(
      input,
      { tenant: 'demo', merchant: 'synthetic' },
      'synthetic-fixture',
      'stable-key',
    );
    const expected = Buffer.from(await webcrypto.subtle.digest('SHA-256', prepared.bytes)).toString(
      'hex',
    );
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(reply({}, 503))
      .mockResolvedValueOnce(reply({ created: false, job }));
    vi.stubGlobal('fetch', fetch);
    await expect(submitUpload(prepared)).rejects.toBeInstanceOf(ApiError);
    await submitUpload(prepared);
    expect(fetch.mock.calls[0][1].body).toBe(prepared.bytes);
    expect(fetch.mock.calls[0][1].headers).toEqual(fetch.mock.calls[1][1].headers);
    expect(fetch.mock.calls[0][1].headers['X-Content-SHA256']).toBe(expected);
    expect(new TextDecoder().decode(prepared.bytes)).toBe('{"synthetic":true}\r\n');
  });
  it('rejects empty/oversized files before reading bytes or hashing', async () => {
    const read = vi.fn();
    const input = { size: 1_048_577, arrayBuffer: read } as unknown as File;
    await expect(
      prepareUpload(input, { tenant: 'demo', merchant: 'synthetic' }, 'source', 'key'),
    ).rejects.toThrow('1 MiB');
    await expect(
      prepareUpload(
        new File([], 'empty.ndjson'),
        { tenant: 'demo', merchant: 'synthetic' },
        'source',
        'key',
      ),
    ).rejects.toThrow('non-empty');
    expect(read).not.toHaveBeenCalled();
  });
  it('recovers an uncertain upload through the UI without changing body, headers or scope', async () => {
    vi.stubGlobal('crypto', webcrypto);
    let post = 0;
    const fetch = fixtureFetch((url, init) =>
      init?.method === 'POST' && url.endsWith('/synthetic')
        ? ++post === 1
          ? Promise.reject(new TypeError('lost'))
          : reply({ created: false, job })
        : undefined,
    );
    render(<FeedWorkspace />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    await user.upload(await screen.findByLabelText('Feed file'), file());
    submitFeed();
    expect(await screen.findByRole('alert')).toHaveTextContent('outcome may be unknown');
    expect(screen.getByLabelText('Source label')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Open feed scope' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Retry identical upload' }));
    expect(await screen.findByText('Existing job recovered.')).toBeInTheDocument();
    const uploads = fetch.mock.calls.filter((call) => call[1]?.method === 'POST');
    expect(uploads[0][1]?.body).toBe(uploads[1][1]?.body);
    expect(uploads[0][1]?.headers).toEqual(uploads[1][1]?.headers);
  });
  it('requires an explicit discard before replacing an unresolved upload', async () => {
    vi.stubGlobal('crypto', webcrypto);
    fixtureFetch((_url, init) => (init?.method === 'POST' ? reply({}, 429) : undefined));
    render(<FeedWorkspace />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    await user.upload(await screen.findByLabelText('Feed file'), file());
    submitFeed();
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Start another upload' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('new key can create another job');
    await user.click(screen.getByRole('button', { name: 'Keep current upload' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry identical upload' })).toBeEnabled();
  });
  it('keeps an unresolved upload when switching workspace navigation', async () => {
    vi.stubGlobal('crypto', webcrypto);
    fixtureFetch((_url, init) => (init?.method === 'POST' ? reply({}, 503) : undefined));
    render(<App />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Merchant feeds' }));
    await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
    await user.upload(await screen.findByLabelText('Feed file'), file());
    submitFeed();
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Verified search' }));
    await user.click(screen.getByRole('button', { name: 'Merchant feeds' }));
    expect(screen.getByRole('button', { name: 'Retry identical upload' })).toBeEnabled();
  });
});
describe('API failures', () => {
  it.each([400, 404, 409, 410, 413, 415, 429, 503])(
    'uses fixed safe messaging for HTTP %i',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(reply({ detail: '<private exception body>' }, status)),
      );
      await expect(request('/api/test')).rejects.toThrow(errorMessage(status));
    },
  );
  it('does not expose raw non-JSON proxy responses as application evidence', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('<html>upstream unavailable</html>', {
          status: 502,
        }),
      ),
    );
    await expect(request('/api/test')).rejects.toThrow('HTTP 502');
  });
});
