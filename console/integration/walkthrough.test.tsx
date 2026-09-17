import { webcrypto } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { App } from '../src/App';
import { prepareUpload, submitUpload } from '../src/api';

it('runs the React feed → real PostgreSQL/Kafka/OpenSearch → verified evidence flow through the Vite proxy', async () => {
  const origin = process.env.CONSOLE_TEST_ORIGIN;
  if (!origin || !/^http:\/\/127\.0\.0\.1:\d+$/.test(origin))
    throw new Error('Run the isolated integration harness; no external backend is allowed.');
  const originalFetch = globalThis.fetch;
  vi.stubGlobal('fetch', (path: string, init?: RequestInit) =>
    originalFetch(new URL(path, origin), init),
  );
  vi.stubGlobal('crypto', webcrypto);
  const tenant = `console-${webcrypto.randomUUID()}`;
  const merchant = 'synthetic';
  const time = new Date(Date.now() - 1000).toISOString();
  const offers = Array.from({ length: 6 }, (_, index) => ({
    tenantId: tenant,
    merchantId: merchant,
    offerId: `keyboard-${index}`,
    version: 1,
    title: `Console acceptance keyboard ${index}`,
    priceMinor: 9900 + index,
    currency: 'USD',
    availableQuantity: 4,
    sourceUpdatedAt: time,
    deleted: false,
  }));
  const text =
    [
      ...offers.map((offer) => JSON.stringify(offer)),
      JSON.stringify(offers[0]),
      'synthetic-invalid-json',
    ].join('\r\n') + '\r\n';
  const bytes = new TextEncoder().encode(text).buffer;
  const file = new File([bytes], 'console-acceptance.ndjson', { type: 'application/x-ndjson' });
  Object.defineProperty(file, 'arrayBuffer', { value: async () => bytes });
  render(<App />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Merchant feeds' }));
  await user.clear(screen.getByLabelText('Feed tenant'));
  await user.type(screen.getByLabelText('Feed tenant'), tenant);
  await user.click(screen.getByRole('button', { name: 'Open feed scope' }));
  await screen.findByText('No jobs in this scope.');
  const key = (screen.getByLabelText('Idempotency key') as HTMLInputElement).value;
  await user.upload(screen.getByLabelText('Feed file'), file);
  // jsdom's FileList facade cannot satisfy its internal native required-file
  // check. All transport, hashing, API calls and backend processing are real.
  fireEvent.submit(screen.getByRole('button', { name: 'Submit feed' }).closest('form')!);
  await screen.findByText('Feed admitted.', {}, { timeout: 15_000 });
  await screen.findByText('Row receipts');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh job' })).toBeEnabled());
  for (let attempt = 0; attempt < 30 && !screen.queryByText('COMPLETED WITH ERRORS'); attempt++) {
    await act(() => delay(300));
    await user.click(screen.getByRole('button', { name: 'Refresh job' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh job' })).toBeEnabled());
  }
  expect(screen.getByText('COMPLETED WITH ERRORS')).toBeInTheDocument();
  expect(screen.getByRole('progressbar')).toHaveAttribute('value', '8');
  expect(screen.getAllByText('APPLIED')).toHaveLength(6);
  expect(screen.getByText('REPLAYED')).toBeInTheDocument();
  expect(screen.getByText('INVALID_JSON')).toBeInTheDocument();
  expect(screen.queryByText('synthetic-invalid-json')).not.toBeInTheDocument();
  const recovered = await submitUpload(
    await prepareUpload(file, { tenant, merchant }, 'synthetic-fixture', key),
  );
  expect(recovered.created).toBe(false);
  expect(recovered.job.applied).toBe(6);
  expect(screen.getByText(recovered.job.id)).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Verified search' }));
  await user.clear(screen.getByLabelText('Tenant'));
  await user.type(screen.getByLabelText('Tenant'), tenant);
  await user.type(screen.getByLabelText('Search catalog'), 'console acceptance keyboard');
  await user.selectOptions(screen.getByLabelText('Page size'), '5');
  // Test-only wait for asynchronous indexing; the application itself never
  // silently reopens snapshots or polls. Each iteration is an explicit search.
  for (let attempt = 0; attempt < 30; attempt++) {
    await user.click(screen.getByRole('button', { name: 'Search offers' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Search offers' })).toBeEnabled(),
    );
    // Five indexed rows can be an exhausted snapshot while the sixth is still
    // in flight. A continuation proves this snapshot includes more candidates.
    if (
      screen.queryAllByRole('button', { name: /Inspect Console acceptance/ }).length === 5 &&
      screen.queryByRole('button', { name: 'Continue search' })
    )
      break;
    await act(() => delay(300));
  }
  expect(screen.getAllByRole('button', { name: /Inspect Console acceptance/ })).toHaveLength(5);
  await user.click(screen.getByRole('button', { name: 'Continue search' }));
  await waitFor(() =>
    expect(screen.getAllByRole('button', { name: /Inspect Console acceptance/ })).toHaveLength(6),
  );
  await user.click(screen.getAllByRole('button', { name: /Inspect Console acceptance/ })[0]);
  const evidence = screen.getByRole('region', { name: 'Selected offer evidence' });
  expect(evidence).toHaveFocus();
  expect(evidence).toHaveTextContent('VERIFIED');
  expect(within(evidence).getByText('Authoritative source')).toBeInTheDocument();
  expect(screen.getByText('$99.00')).toBeInTheDocument();
  // Source changes behind the PIT must never be trusted merely because the
  // index still contains the original candidate.
  const changed = await originalFetch(new URL('/api/v1/offers', origin), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...offers[0], version: 2, deleted: true }),
  });
  expect(changed.status).toBe(200);
  await user.click(screen.getByRole('button', { name: 'Search offers' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Search offers' })).toBeEnabled());
  expect(
    screen.queryByRole('button', { name: 'Inspect Console acceptance keyboard 0 from synthetic' }),
  ).not.toBeInTheDocument();
});
