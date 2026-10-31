import { afterEach, expect, it, vi } from 'vitest'
import { getCandles, getMarketDepth, getRecentTrades } from './marketData'
const fetchMock = vi.fn()
function respond(data: unknown) { vi.stubGlobal('fetch', fetchMock.mockResolvedValue(new Response(JSON.stringify(data)))) }
afterEach(() => { vi.unstubAllGlobals(); vi.resetAllMocks() })
const candle = { startTime: '2026-10-31T14:32:00Z', open: 100, high: 105, low: 99, close: 102, volume: 20, tradeCount: 3 }
it('requests five depth levels and preserves sorted asymmetric sides', async () => {
  const data = { symbol: 'AAPL', bids: [{ price: 99, quantity: 3 }, { price: 98, quantity: 2 }], asks: [] }
  respond(data)
  expect(await getMarketDepth('AAPL')).toEqual(data)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/stocks/AAPL/depth?levels=5')
})
it('rejects unsorted depth and wrong symbols', async () => {
  respond({ symbol: 'AAPL', bids: [{ price: 98, quantity: 2 }, { price: 99, quantity: 3 }], asks: [] })
  await expect(getMarketDepth('AAPL')).rejects.toThrow('invalid')
  respond({ symbol: 'TSLA', bids: [], asks: [] })
  await expect(getMarketDepth('AAPL')).rejects.toThrow('invalid')
})
it('requests newest ten trades by ID without resorting timestamps', async () => {
  const data = { page: 0, size: 10, totalElements: 2, totalPages: 1, content: [
    { tradeId: 2, symbol: 'AAPL', executionPrice: 100, quantity: 2, executedAt: '2026-10-31T14:32:00.123456Z' },
    { tradeId: 1, symbol: 'AAPL', executionPrice: 101, quantity: 3, executedAt: '2026-10-31T15:32:00Z' },
  ] }
  respond(data)
  expect(await getRecentTrades('AAPL')).toEqual(data)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/stocks/AAPL/trades?page=0&size=10')
})
it('rejects unsafe quantities and invalid timestamps', async () => {
  for (const trade of [
    { quantity: Number.MAX_SAFE_INTEGER + 1, executedAt: '2026-10-31T14:32:00Z' },
    { quantity: 1, executedAt: '2026-02-30T14:32:00Z' },
  ]) {
    respond({ page: 0, size: 10, totalElements: 1, totalPages: 1, content: [{ tradeId: 1, symbol: 'AAPL', executionPrice: 100, ...trade }] })
    await expect(getRecentTrades('AAPL')).rejects.toThrow('invalid')
  }
})
it('requests latest hundred candles and keeps chronological gaps', async () => {
  const data = [candle, { ...candle, startTime: '2026-10-31T14:40:00Z' }]
  respond(data)
  expect(await getCandles('AAPL')).toEqual(data)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/stocks/AAPL/candles?limit=100')
})
it('rejects duplicate buckets, invalid OHLC and non-minute timestamps', async () => {
  for (const data of [[candle, candle], [{ ...candle, high: 98 }], [{ ...candle, startTime: '2026-10-31T14:32:01Z' }]]) {
    respond(data)
    await expect(getCandles('AAPL')).rejects.toThrow('invalid')
  }
})
it('accepts empty history and empty candles', async () => {
  respond({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
  expect((await getRecentTrades('AAPL')).content).toEqual([])
  respond([])
  expect(await getCandles('AAPL')).toEqual([])
})
