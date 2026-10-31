import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MarketDashboard } from './MarketDashboard'
import { getStocks } from '../api/stocks'
import { getMarketSnapshot } from '../api/marketData'
import type { MarketSnapshot } from '../types/market'

vi.mock('../components/MarketPanels', () => ({ MarketPanels: () => null }))
vi.mock('../api/stocks')
vi.mock('../api/marketData')

const stocks = [
  { symbol: 'XYZ', companyName: 'Example Company', referencePrice: 100 },
  { symbol: 'ABC', companyName: 'Another Company', referencePrice: 100 },
]
const empty: MarketSnapshot = {
  symbol: 'XYZ', lastTradedPrice: null, priceChange: null, priceChangePercent: null,
  bestBidPrice: null, bestAskPrice: null, spread: null, volume: 0, tradeCount: 0, openOrderCount: 0, vwap: null,
}
const populated: MarketSnapshot = {
  ...empty, lastTradedPrice: 102.48, priceChange: 2.48, priceChangePercent: 2.48,
  bestBidPrice: 102.4, bestAskPrice: 102.55, spread: 0.15, volume: 12430, tradeCount: 421, vwap: 101.87,
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getStocks).mockResolvedValue(stocks)
  vi.mocked(getMarketSnapshot).mockImplementation(async (symbol) => ({ ...empty, symbol }))
})
afterEach(cleanup)

it('selects the first returned stock, renders null prices as dashes and zero counters as zero', async () => {
  render(<MarketDashboard />)
  const summary = await screen.findByRole('region', { name: 'XYZ' })
  await screen.findByText('No executions yet.', { exact: false })
  expect(getMarketSnapshot).toHaveBeenCalledWith('XYZ', expect.any(AbortSignal))
  expect(screen.getByRole('button', { name: 'XYZ Example Company' }).getAttribute('aria-pressed')).toBe('true')
  expect(within(summary).getAllByText('—').length).toBeGreaterThan(0)
  expect(within(summary).getAllByText('0').length).toBe(3)
  expect(within(summary).queryByText('0.00')).toBeNull()
})

it('shows execution prices, signed changes and formatted counters', async () => {
  vi.mocked(getMarketSnapshot).mockResolvedValue(populated)
  render(<MarketDashboard />)
  expect(await screen.findByText('102.48')).toBeTruthy()
  expect(screen.getByText('+2.48').closest('p')?.className).toContain('positive')
  expect(screen.getByText('(+2.48%)')).toBeTruthy()
  expect(screen.getByText('12,430')).toBeTruthy()
  expect(screen.getByText('101.87')).toBeTruthy()
})

it('switches symbols and formats negative change in restrained red', async () => {
  render(<MarketDashboard />)
  await screen.findByText('No executions yet.', { exact: false })
  vi.mocked(getMarketSnapshot).mockResolvedValue({ ...populated, symbol: 'ABC', priceChange: -2, priceChangePercent: -2 })
  fireEvent.click(screen.getByRole('button', { name: 'ABC Another Company' }))
  expect(await screen.findByText('-2.00')).toBeTruthy()
  expect(screen.getByText('-2.00').closest('p')?.className).toContain('negative')
  expect(screen.getByRole('region', { name: 'ABC' })).toBeTruthy()
})

it('shows list loading and an empty state without requesting a snapshot', async () => {
  let resolve!: (value: typeof stocks) => void
  vi.mocked(getStocks).mockReturnValue(new Promise((done) => { resolve = done }))
  render(<MarketDashboard />)
  expect(screen.getByRole('status').textContent).toContain('Loading registered stocks')
  await act(async () => resolve([]))
  expect(screen.getByText('No stocks registered')).toBeTruthy()
  expect(getMarketSnapshot).not.toHaveBeenCalled()
})

it('retries a failed stock list', async () => {
  vi.mocked(getStocks).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(stocks)
  render(<MarketDashboard />)
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: /Retry stocks/ }))
  expect(await screen.findByRole('region', { name: 'XYZ' })).toBeTruthy()
})

it('keeps stock selection available when one summary fails and supports retry', async () => {
  vi.mocked(getMarketSnapshot).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(empty)
  render(<MarketDashboard />)
  await screen.findByText('Summary unavailable')
  expect(screen.getByRole('button', { name: 'ABC Another Company' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: /Retry summary/ }))
  expect(await screen.findByText('No executions yet.', { exact: false })).toBeTruthy()
})

it('cancels old selections and ignores late responses even if the request ignores abort', async () => {
  let oldResolve!: (value: MarketSnapshot) => void
  vi.mocked(getMarketSnapshot).mockImplementation((symbol) => symbol === 'XYZ'
    ? new Promise((done) => { oldResolve = done })
    : Promise.resolve({ ...populated, symbol: 'ABC' }))
  render(<MarketDashboard />)
  await waitFor(() => expect(getMarketSnapshot).toHaveBeenCalledTimes(1))
  const oldSignal = vi.mocked(getMarketSnapshot).mock.calls[0][1]
  fireEvent.click(screen.getByRole('button', { name: 'ABC Another Company' }))
  await screen.findByText('102.48')
  expect(oldSignal?.aborted).toBe(true)
  await act(async () => oldResolve({ ...empty, lastTradedPrice: 999 }))
  expect(screen.queryByText('999.00')).toBeNull()
  expect(screen.getByRole('region', { name: 'ABC' })).toBeTruthy()
})

it('does not show the previous stock price while a new selection is loading', async () => {
  vi.mocked(getMarketSnapshot).mockResolvedValueOnce(populated).mockReturnValue(new Promise(() => {}))
  render(<MarketDashboard />)
  await screen.findByText('102.48')
  fireEvent.click(screen.getByRole('button', { name: 'ABC Another Company' }))
  expect(screen.queryByText('102.48')).toBeNull()
  expect(screen.getByRole('status').textContent).toContain('Loading ABC')
})

it('handles StrictMode cleanup and aborts snapshot requests on unmount', async () => {
  const view = render(<StrictMode><MarketDashboard /></StrictMode>)
  await screen.findByText('No executions yet.', { exact: false })
  const calls = vi.mocked(getMarketSnapshot).mock.calls
  view.unmount()
  expect(calls.at(-1)?.[1]?.aborted).toBe(true)
})

it('ignores an old success and an old error after switching away and back', async () => {
  let oldSuccess!: (value: MarketSnapshot) => void
  let oldFailure!: (reason: Error) => void
  vi.mocked(getMarketSnapshot)
    .mockReturnValueOnce(new Promise((done) => { oldSuccess = done }))
    .mockReturnValueOnce(new Promise((_done, fail) => { oldFailure = fail }))
    .mockResolvedValue(populated)
  render(<MarketDashboard />)
  await waitFor(() => expect(getMarketSnapshot).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: 'ABC Another Company' }))
  await waitFor(() => expect(getMarketSnapshot).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: 'XYZ Example Company' }))
  await screen.findByText('102.48')
  await act(async () => {
    oldSuccess({ ...empty, lastTradedPrice: 999 })
    oldFailure(new Error('Late failure'))
  })
  expect(screen.queryByText('999.00')).toBeNull()
  expect(screen.queryByText('Summary unavailable')).toBeNull()
  expect(screen.getByText('102.48')).toBeTruthy()
})

it('clicking the already-selected stock does not leave the summary stuck loading', async () => {
  render(<MarketDashboard />)
  await screen.findByText('No executions yet.', { exact: false })
  fireEvent.click(screen.getByRole('button', { name: 'XYZ Example Company' }))
  expect(screen.queryByText('Loading XYZ market summary…')).toBeNull()
  expect(getMarketSnapshot).toHaveBeenCalledTimes(1)
})
