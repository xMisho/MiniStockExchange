import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { RefreshStatus } from './RefreshStatus'

afterEach(cleanup)
it('shows successful UTC update time and a subtle retry warning for stale data', () => {
  const retry = vi.fn()
  render(<RefreshStatus updatedAt={Date.parse('2026-10-31T14:32:08Z')} refreshing={false} refreshError onRetry={retry} />)
  expect(screen.getByText('Last updated 14:32:08 UTC')).toBeTruthy()
  expect(screen.getByRole('status').textContent).toContain('showing previous data')
  fireEvent.click(screen.getByRole('button', { name: 'Retry refresh' }))
  expect(retry).toHaveBeenCalledOnce()
})
it('hides update metadata before the first successful response', () => {
  const { container } = render(<RefreshStatus updatedAt={null} refreshing refreshError={false} onRetry={vi.fn()} />)
  expect(container.textContent).toBe('')
})
