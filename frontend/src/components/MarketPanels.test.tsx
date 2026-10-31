import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MarketPanels } from './MarketPanels'
import { getCandles, getMarketDepth, getRecentTrades } from '../api/marketData'

vi.mock('../api/marketData')
vi.mock('./CandlestickCanvas', () => ({ CandlestickCanvas: () => <div>Rendered candlesticks</div> }))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getMarketDepth).mockImplementation(async (symbol) => ({ symbol, bids: [], asks: [] }))
  vi.mocked(getRecentTrades).mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
  vi.mocked(getCandles).mockResolvedValue([])
})
afterEach(cleanup)

it('distinguishes empty data from errors in all panels', async () => {
  render(<MarketPanels symbol="AAPL" />)
  expect(await screen.findByText('No resting orders yet.')).toBeTruthy()
  expect(await screen.findByText('No executions yet.')).toBeTruthy()
  expect(await screen.findByText(/No candles yet/)).toBeTruthy()
  expect(screen.queryByRole('alert')).toBeNull()
})

it('keeps requests independent and retries only the failed panel', async () => {
  vi.mocked(getMarketDepth).mockRejectedValueOnce(new Error('offline'))
  render(<MarketPanels symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Retry depth' }))
  await screen.findByText('No resting orders yet.')
  expect(getMarketDepth).toHaveBeenCalledTimes(2)
  expect(getRecentTrades).toHaveBeenCalledTimes(1)
  expect(getCandles).toHaveBeenCalledTimes(1)
})

it('renders asymmetric depth and preserves returned price order', async () => {
  vi.mocked(getMarketDepth).mockResolvedValue({ symbol: 'AAPL', bids: [{ price: 99, quantity: 1200 }, { price: 98, quantity: 2 }], asks: [{ price: 101, quantity: 5 }] })
  render(<MarketPanels symbol="AAPL" />)
  await screen.findByText('1,200')
  const rows = within(screen.getByRole('region', { name: 'AAPL market depth' })).getAllByRole('row')
  expect(rows[1].textContent).toBe('99.001,200101.005')
  expect(rows[2].textContent).toBe('98.002——')
})

it('keeps trade ID order even when timestamps go backward and displays UTC', async () => {
  vi.mocked(getRecentTrades).mockResolvedValue({ page: 0, size: 10, totalElements: 2, totalPages: 1, content: [
    { tradeId: 9, symbol: 'AAPL', executionPrice: 102, quantity: 3, executedAt: '2026-10-31T14:32:08.123456Z' },
    { tradeId: 8, symbol: 'AAPL', executionPrice: 101, quantity: 4, executedAt: '2026-10-31T15:32:08Z' },
  ] })
  render(<MarketPanels symbol="AAPL" />)
  await screen.findByText('14:32:08 UTC')
  const rows = within(screen.getByRole('region', { name: 'AAPL recent trades' })).getAllByRole('row')
  expect(rows[1].textContent).toContain('102.00')
  expect(rows[2].textContent).toContain('101.00')
})

it('renders candles and an exact-value table independently of depth errors', async () => {
  vi.mocked(getMarketDepth).mockRejectedValue(new Error('offline'))
  vi.mocked(getCandles).mockResolvedValue([{ startTime: '2026-10-31T14:32:00Z', open: 100, high: 105, low: 99, close: 102, volume: 20, tradeCount: 3 }])
  render(<MarketPanels symbol="AAPL" />)
  expect(await screen.findByText('Rendered candlesticks')).toBeTruthy()
  fireEvent.click(screen.getByText('View candle data'))
  expect(screen.getByText('105.00')).toBeTruthy()
  expect(screen.getByText('2026-10-31 14:32:00 UTC')).toBeTruthy()
})

it('aborts all old requests and ignores late responses after switching symbols', async () => {
  let resolveOld!: (value: { symbol: string; bids: []; asks: [] }) => void
  vi.mocked(getMarketDepth).mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
  const view = render(<MarketPanels key="AAPL" symbol="AAPL" />)
  await screen.findByText('No executions yet.')
  const signals = [vi.mocked(getMarketDepth).mock.calls[0][1], vi.mocked(getRecentTrades).mock.calls[0][1], vi.mocked(getCandles).mock.calls[0][1]]
  view.rerender(<MarketPanels key="TSLA" symbol="TSLA" />)
  await screen.findByText('No resting orders yet.')
  expect(signals.every((signal) => signal?.aborted)).toBe(true)
  await act(async () => resolveOld({ symbol: 'AAPL', bids: [], asks: [] }))
  expect(screen.queryByRole('region', { name: 'AAPL market depth' })).toBeNull()
  await waitFor(() => expect(getMarketDepth).toHaveBeenCalledTimes(2))
})

it.each(['trades', 'candles'] as const)('retries only %s when its request fails', async (name) => {
  const loader = name === 'trades' ? vi.mocked(getRecentTrades) : vi.mocked(getCandles)
  loader.mockRejectedValueOnce(new Error('offline'))
  render(<MarketPanels symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: `Retry ${name}` }))
  await screen.findByText(name === 'trades' ? 'No executions yet.' : /No candles yet/)
  expect(getMarketDepth).toHaveBeenCalledTimes(1)
  expect(getRecentTrades).toHaveBeenCalledTimes(name === 'trades' ? 2 : 1)
  expect(getCandles).toHaveBeenCalledTimes(name === 'candles' ? 2 : 1)
})

it('ignores a late failure for the previous stock', async () => {
  let rejectOld!: (reason: Error) => void
  vi.mocked(getCandles).mockImplementationOnce(() => new Promise((_, reject) => { rejectOld = reject }))
  const view = render(<MarketPanels key="AAPL" symbol="AAPL" />)
  await screen.findByText('No executions yet.')
  view.rerender(<MarketPanels key="TSLA" symbol="TSLA" />)
  await screen.findByText(/No candles yet/)
  await act(async () => rejectOld(new Error('late failure')))
  expect(screen.queryByRole('alert')).toBeNull()
})
