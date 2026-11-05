import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SymbolSearch } from './SymbolSearch'
import { AdminStocks } from './AdminStocks'
import { PaperPortfolio } from './PaperPortfolio'
import { PriceAlerts } from './PriceAlerts'
import { MarketSession } from './MarketSession'
import { useAuth } from '../../auth/authState'
import * as api from '../../api/workspace'
vi.mock('../../api/workspace')
vi.mock('../../auth/authState')
const user = { id: 1, username: 'example', email: 'example@example.test', role: 'ADMIN' as const }
const auth = { user, status: 'authenticated' as const, busy: false, error: null, holdSession: () => () => {}, restore: vi.fn(), signIn: vi.fn(), signOut: vi.fn() }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(useAuth).mockReturnValue(auth) })
afterEach(() => { cleanup(); vi.useRealTimers() })
it('debounces search and selects provider instruments', async () => {
  vi.useFakeTimers()
  const item = { symbol: 'NVDA', companyName: 'NVIDIA Corporation', currency: 'USD' as const }
  vi.mocked(api.searchInstruments).mockResolvedValue([item])
  const selected = vi.fn()
  render(<SymbolSearch onSelect={selected} />)
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'NVDA' } })
  await act(async () => vi.advanceTimersByTime(300))
  fireEvent.click(screen.getByRole('button', { name: /NVDA.*NVIDIA/ }))
  expect(selected).toHaveBeenCalledWith(item)
  expect(api.searchInstruments).toHaveBeenCalledWith('NVDA', expect.any(AbortSignal))
})
it('does not display admin registration controls for regular users', () => {
  vi.mocked(useAuth).mockReturnValue({ ...auth, user: { ...user, role: 'USER' } })
  const view = render(<AdminStocks onRegistered={vi.fn()} />)
  expect(view.container.textContent).toBe('')
})
it('registers a normalized local stock and reports backend conflicts', async () => {
  vi.mocked(api.registerStock).mockResolvedValue(undefined)
  const changed = vi.fn()
  render(<AdminStocks onRegistered={changed} />)
  fireEvent.click(screen.getByText('Admin · Register local stock'))
  fireEvent.change(screen.getByLabelText('Stock symbol'), { target: { value: ' nvda ' } })
  fireEvent.change(screen.getByLabelText('Company name'), { target: { value: 'NVIDIA' } })
  fireEvent.change(screen.getByLabelText('Reference price'), { target: { value: '120.25' } })
  fireEvent.click(screen.getByRole('button', { name: 'Register stock' }))
  await waitFor(() => expect(api.registerStock).toHaveBeenCalledWith('NVDA', 'NVIDIA', '120.25'))
  expect(await screen.findByText('Registered NVDA in the local exchange.')).toBeTruthy()
  expect(changed).toHaveBeenCalledOnce()
})
it('shows virtual cash and local position accounting with clear limitations', async () => {
  vi.mocked(api.getPortfolio).mockResolvedValue({ startingCash: 100000, cash: 99480, equity: 100200, realizedPnl: 80, unrealizedPnl: 120, positions: [{ symbol: 'AAPL', quantity: 6, averagePrice: 100, markPrice: 120, marketValue: 720, realizedPnl: 80, unrealizedPnl: 120 }] })
  render(<PaperPortfolio active />)
  expect((await screen.findAllByText('99,480.00'))[0]).toBeTruthy()
  expect(screen.getByText('AAPL')).toBeTruthy()
  expect(screen.getByText(/Open limit orders reserve cash or shares/)).toBeTruthy()
})
it('arms a sampled crossing alert and never automatically retries its creation', async () => {
  vi.mocked(api.getAlerts).mockResolvedValue({ alerts: [], warning: null })
  vi.mocked(api.createAlert).mockResolvedValue(undefined)
  render(<PriceAlerts symbol="AAPL" />)
  fireEvent.change(screen.getByLabelText('Target price'), { target: { value: '350' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create alert' }))
  await waitFor(() => expect(api.createAlert).toHaveBeenCalledWith('AAPL', 'ABOVE', '350'))
  expect(api.createAlert).toHaveBeenCalledOnce()
  expect(screen.getByText(/No background, email, or push delivery/)).toBeTruthy()
})
it('uses provider session status and retains a stale-status warning', async () => {
  vi.mocked(api.getSession).mockResolvedValue({ provider: 'Alpaca', feed: 'IEX', currency: 'USD', coverage: 'IEX', fetchedAt: '2026-11-02T13:00:00Z', stale: true, warning: 'Unavailable', data: { status: 'PRE_MARKET', asOf: '2026-11-02T13:00:00Z', nextOpen: '2026-11-02T14:30:00Z', nextClose: '2026-11-02T21:00:00Z', regularOpen: '2026-11-02T14:30:00Z', regularClose: '2026-11-02T21:00:00Z', timezone: 'America/New_York' } })
  render(<MarketSession />)
  expect(await screen.findByText('Pre-market')).toBeTruthy()
  expect(screen.getByText(/Session status could not refresh/)).toBeTruthy()
})

it('announces newly triggered alerts once and preserves the triggered badge after dismissal', async () => {
  vi.useFakeTimers()
  const alert = { id: 7, symbol: 'AAPL', direction: 'ABOVE' as const, target: 350, triggeredAt: null as string | null, triggeredPrice: null as number | null, lastObservedAt: '2026-11-02T14:00:00Z' }
  const fired = { ...alert, triggeredAt: '2026-11-02T14:01:00Z', triggeredPrice: 351 }
  vi.mocked(api.getAlerts).mockResolvedValueOnce({ alerts: [alert], warning: null, newlyTriggeredIds: [] })
    .mockResolvedValue({ alerts: [fired], warning: null, newlyTriggeredIds: [7] })
  render(<PriceAlerts symbol="AAPL" />)
  await act(async () => {})
  expect(screen.queryByText('Price alert triggered')).toBeNull()
  await act(async () => vi.advanceTimersByTime(15000))
  expect(screen.getByText('Price alert triggered')).toBeTruthy()
  expect(screen.getByText('AAPL crossed above 350.00 at 351.00.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Dismiss price alert notification' }))
  expect(screen.getByText('Triggered', { selector: '.alert-badge' })).toBeTruthy()
  await act(async () => vi.advanceTimersByTime(15000))
  expect(screen.queryByText('Price alert triggered')).toBeNull()
})
it('does not toast historical triggers again when reopening the panel', async () => {
  vi.mocked(api.getAlerts).mockResolvedValue({ alerts: [{ id: 7, symbol: 'AAPL', direction: 'ABOVE', target: 350, triggeredAt: '2026-11-02T14:00:00Z', triggeredPrice: 351, lastObservedAt: '2026-11-02T14:00:00Z' }], warning: null, newlyTriggeredIds: [] })
  render(<PriceAlerts symbol="AAPL" />)
  expect(await screen.findByText('Triggered', { selector: '.alert-badge' })).toBeTruthy()
  expect(screen.queryByText('Price alert triggered')).toBeNull()
})
it('supports arrow-key search navigation, Escape and focus restoration', async () => {
  vi.useFakeTimers()
  vi.mocked(api.searchInstruments).mockResolvedValue([{ symbol: 'NVDA', companyName: 'NVIDIA', currency: 'USD' }, { symbol: 'NVDX', companyName: 'Other stock', currency: 'USD' }])
  const selected = vi.fn()
  render(<SymbolSearch onSelect={selected} />)
  const input = screen.getByRole('searchbox')
  fireEvent.change(input, { target: { value: 'NVD' } })
  await act(async () => vi.advanceTimersByTime(300))
  fireEvent.keyDown(input, { key: 'ArrowDown' })
  const first = screen.getByRole('button', { name: /NVDA.*NVIDIA/ })
  expect(document.activeElement).toBe(first)
  fireEvent.keyDown(first, { key: 'ArrowDown' })
  expect(document.activeElement).toBe(screen.getByRole('button', { name: /NVDX.*Other stock/ }))
  fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
  expect(document.activeElement).toBe(input)
  expect(screen.queryByRole('button')).toBeNull()
})

it('lets an Apple-chart user arm a Tesla alert with an explicit independent stock choice', async () => {
  vi.mocked(api.getAlerts).mockResolvedValue({ alerts: [], warning: null })
  vi.mocked(api.createAlert).mockResolvedValue(undefined)
  render(<PriceAlerts symbol="AAPL" />)
  const stock = screen.getByRole('combobox', { name: 'Alert stock' })
  fireEvent.change(screen.getByLabelText('Target price'), { target: { value: '350' } })
  fireEvent.change(stock, { target: { value: 'TSLA' } })
  expect((screen.getByLabelText('Target price') as HTMLInputElement).value).toBe('')
  fireEvent.change(screen.getByLabelText('Target price'), { target: { value: '450' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create alert' }))
  await waitFor(() => expect(api.createAlert).toHaveBeenCalledWith('TSLA', 'ABOVE', '450'))
  expect(api.createAlert).toHaveBeenCalledOnce()
})
it('searches additional alert symbols and preserves the chosen alert stock when the chart changes', async () => {
  vi.mocked(api.getAlerts).mockResolvedValue({ alerts: [], warning: null })
  vi.mocked(api.searchInstruments).mockResolvedValue([{ symbol: 'NVDA', companyName: 'NVIDIA Corporation', currency: 'USD' }])
  vi.mocked(api.createAlert).mockResolvedValue(undefined)
  const view = render(<PriceAlerts symbol="AAPL" />)
  fireEvent.change(screen.getByRole('searchbox', { name: 'Find alert stock' }), { target: { value: 'NVDA' } })
  fireEvent.click(await screen.findByRole('button', { name: /NVDA.*NVIDIA/ }))
  expect((screen.getByRole('combobox', { name: 'Alert stock' }) as HTMLSelectElement).value).toBe('NVDA')
  view.rerender(<PriceAlerts symbol="TSLA" />)
  expect((screen.getByRole('combobox', { name: 'Alert stock' }) as HTMLSelectElement).value).toBe('NVDA')
  fireEvent.change(screen.getByLabelText('Target price'), { target: { value: '180' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create alert' }))
  await waitFor(() => expect(api.createAlert).toHaveBeenCalledWith('NVDA', 'ABOVE', '180'))
})
