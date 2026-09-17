import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { expect, it, vi } from 'vitest';
import { App } from '../App';
import { identifier, timestamp } from '../api';

it('provides named controls and landmarks on both initial workspaces', async () => {
  vi.stubGlobal('fetch', vi.fn());
  render(<App />);
  const options = { rules: { 'color-contrast': { enabled: false } } };
  // jsdom has no layout engine; contrast/viewport/native-dialog behavior require
  // a real browser pass. Do not describe this as full WCAG certification.
  expect((await axe.run(document.body, options)).violations).toEqual([]);
  await userEvent.click(screen.getByRole('button', { name: 'Merchant feeds' }));
  expect((await axe.run(document.body, options)).violations).toEqual([]);
});

it('accepts hyphenated identifiers with modern HTML pattern syntax', () => {
  const pattern = new RegExp(`^(?:${identifier})$`, 'v');
  expect(pattern.test('synthetic-fixture')).toBe(true);
  expect(pattern.test('e035d3a7-823a-4851-a100-26464dc02755')).toBe(true);
  expect(pattern.test('../tenant')).toBe(false);
  expect(pattern.test('x'.repeat(65))).toBe(false);
});

it('preserves source microseconds when displaying evidence', () => {
  expect(timestamp('2026-09-16T12:00:00.123456Z')).toBe('2026-09-16 12:00:00.123456 UTC');
});
