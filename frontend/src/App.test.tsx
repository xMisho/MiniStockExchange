import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { VerdexWorkspace } from './App'
vi.mock('./auth/authState', () => ({ useAuth: () => ({ user: null }) }))
vi.mock('./paper/PaperTrading', () => ({ PaperTrading: () => <input aria-label="Paper ticket draft" defaultValue="" /> }))
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
  expect(within(screen.getByRole('navigation', { name: 'Developer sandbox' })).getByRole('link', { name: 'Matching Sandbox' }).getAttribute('aria-current')).toBe('page')
  expect(nav.queryByRole('link', { name: 'Matching Sandbox' })).toBeNull()
  expect(nav.getByRole('link', { name: 'Paper Trading' })).toBeTruthy()
  expect(document.title).toBe('Verdex | Matching Sandbox')
  await act(async () => { window.location.hash = '/orders'; window.dispatchEvent(new HashChangeEvent('hashchange')) })
  expect(screen.getByTestId('local-view').textContent).toBe('hidden')
  expect(nav.getByRole('link', { name: 'My Orders' }).getAttribute('aria-current')).toBe('page')
  await act(async () => { window.location.hash = '/markets'; window.dispatchEvent(new HashChangeEvent('hashchange')) })
  expect(screen.getByRole('heading', { name: 'External markets' })).toBeTruthy()
  expect(screen.getByTestId('local-view').textContent).toBe('hidden')
})

it('keeps the paper workspace mounted across orders, portfolio and markets navigation',async()=>{
 vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
 window.history.replaceState(null,'','/#/paper');render(<VerdexWorkspace/>);
 const draft=screen.getByLabelText('Paper ticket draft');fireEvent.change(draft,{target:{value:'TSLA BUY 7'}})
 for(const route of ['orders','portfolio','markets','paper']){
  await act(async()=>{window.location.hash='/'+route;window.dispatchEvent(new HashChangeEvent('hashchange'))})
 }
 expect(screen.getByLabelText('Paper ticket draft')).toBe(draft)
 expect((draft as HTMLInputElement).value).toBe('TSLA BUY 7')
})
