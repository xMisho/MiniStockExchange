import { act, cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { VerdexWorkspace } from './App'
vi.mock('./auth/authState', () => ({ useAuth: () => ({ user: null }) }))
vi.mock('./components/AccountMenu', () => ({ AccountMenu: () => <div>Account controls</div> }))
vi.mock('./pages/ExternalMarketDashboard', () => ({ ExternalMarketDashboard: () => <h1>External markets</h1> }))
vi.mock('./pages/LocalExchange', () => ({ LocalExchange: ({ view }: { view: string }) => <div data-testid="local-view">{view}</div> }))
afterEach(() => { cleanup(); window.history.replaceState(null, '', '/'); vi.restoreAllMocks() })
it('brands the shell as Verdex and reflects direct links and browser hash navigation', async () => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  window.history.replaceState(null, '', '/#/exchange')
  render(<VerdexWorkspace />)
  expect(screen.getByRole('link', { name: 'Verdex home' }).textContent).toContain('Verdex')
  const nav = within(screen.getByRole('navigation', { name: 'Primary navigation' }))
  expect(nav.getByRole('link', { name: 'Matching Sandbox' }).getAttribute('aria-current')).toBe('page')
  expect(document.title).toBe('Verdex | Matching Sandbox')
  await act(async () => { window.location.hash = '/orders'; window.dispatchEvent(new HashChangeEvent('hashchange')) })
  expect(screen.getByTestId('local-view').textContent).toBe('hidden')
  expect(nav.getByRole('link', { name: 'My Orders' }).getAttribute('aria-current')).toBe('page')
  await act(async () => { window.location.hash = '/markets'; window.dispatchEvent(new HashChangeEvent('hashchange')) })
  expect(screen.getByRole('heading', { name: 'External markets' })).toBeTruthy()
  expect(screen.getByTestId('local-view').textContent).toBe('hidden')
})
