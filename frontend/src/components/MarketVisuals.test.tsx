import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { VolumeChart } from './VolumeChart'
import { UpdateStatus } from './UpdateStatus'
import { PriceValue } from './PriceValue'
import { StockList } from './StockList'
import { getMarketSnapshot } from '../api/marketData'
import type { MarketSnapshot } from '../types/market'

vi.mock('../api/marketData')
const snapshot: MarketSnapshot = { symbol: 'AAPL', lastTradedPrice: 100, priceChange: 1, priceChangePercent: 1, bestBidPrice: null, bestAskPrice: null, spread: null, volume: 10, tradeCount: 2, openOrderCount: 0, vwap: 99 }
beforeEach(() => { vi.mocked(getMarketSnapshot).mockImplementation(async symbol => ({ ...snapshot, symbol })) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.resetAllMocks() })
it('shows actual execution volume and keeps idle minutes omitted', () => {
  render(<VolumeChart symbol="AAPL" candles={[
    { startTime: '2026-11-01T10:00:00Z', open: 100, high: 101, low: 99, close: 101, volume: 10 },
    { startTime: '2026-11-01T10:05:00Z', open: 101, high: 102, low: 100, close: 100, volume: 20 },
  ]} />)
  const chart = screen.getByRole('img', { name: /Peak 20 shares/ })
  expect(chart.children).toHaveLength(2)
  expect((chart.children[0] as HTMLElement).style.height).toBe('50%')
  expect((chart.children[1] as HTMLElement).style.height).toBe('100%')
  expect(screen.getByText('2026-11-01 10:00:00 UTC')).toBeTruthy()
  expect(screen.getByText('2026-11-01 10:05:00 UTC')).toBeTruthy()
})
it('renders no volume chart when there are no executions', () => {
  const { container } = render(<VolumeChart symbol="AAPL" candles={[]} />)
  expect(container.textContent).toBe('')
})
it('reports connection, retry and paused states without pretending polling is a stream', () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const view = render(<UpdateStatus state="ready" refreshError={false} />)
  expect(screen.getByRole('status').textContent).toBe('Auto-refresh on')
  view.rerender(<UpdateStatus state="ready" refreshError />)
  expect(screen.getByRole('status').textContent).toBe('Retrying updates')
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  fireEvent(document, new Event('visibilitychange'))
  expect(screen.getByRole('status').textContent).toBe('Updates paused')
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  fireEvent(window, new Event('offline'))
  expect(screen.getByRole('status').textContent).toBe('Offline')
})
it('flashes real price changes and respects reduced-motion preferences', () => {
  const cancel = vi.fn(), animate = vi.fn(() => ({ cancel }))
  Object.defineProperty(HTMLElement.prototype, 'animate', { configurable: true, value: animate })
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))
  const view = render(<PriceValue value={100} />)
  expect(animate).not.toHaveBeenCalled()
  view.rerender(<PriceValue value={101} />)
  expect(animate).toHaveBeenCalledOnce()
  view.rerender(<PriceValue value={101} />)
  expect(animate).toHaveBeenCalledOnce()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
  view.rerender(<PriceValue value={99} />)
  expect(animate).toHaveBeenCalledOnce()
  expect(cancel).toHaveBeenCalledOnce()
  delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate
  vi.unstubAllGlobals()
})
it('bounds quote previews and reuses the selected stock snapshot', async () => {
  const stocks = Array.from({ length: 8 }, (_, index) => ({ symbol: index === 0 ? 'AAPL' : `TEST${index}`, companyName: `Company ${index}`, referencePrice: 100 }))
  render(<StockList stocks={stocks} selectedSymbol="AAPL" snapshot={snapshot} onSelect={vi.fn()} />)
  await act(async () => {})
  expect(getMarketSnapshot).toHaveBeenCalledTimes(5)
  expect(getMarketSnapshot).not.toHaveBeenCalledWith('AAPL', expect.anything())
  expect(within(screen.getByRole('button', { name: 'AAPL Company 0' })).getByText('100.00')).toBeTruthy()
  expect(screen.getAllByRole('button')).toHaveLength(8)
})
