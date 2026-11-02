import { afterEach, expect, it, vi } from 'vitest'
import { getExternalHistory, getExternalQuote, getMarketConnection } from './externalMarket'
const wrap = (data: unknown) => ({ provider: 'Alpaca', feed: 'IEX', currency: 'USD', coverage: 'Single exchange', fetchedAt: '2026-11-01T12:00:00Z', stale: false, warning: null, data })
const quote = { symbol: 'AAPL', price: 102, priceAsOf: '2026-10-30T19:00:00Z', bid: null, ask: null, quoteAsOf: null, previousClose: 100, change: 2, changePercent: 2, sessionOpen: 101, sessionHigh: 103, sessionLow: 99, sessionVolume: 10.5, sessionTradeCount: 4, sessionVwap: 101.5, sessionDate: '2026-10-30' }
function response(body: unknown, status = 200) { return vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))) }
afterEach(() => vi.unstubAllGlobals())
it('requests quotes through the backend without browser provider credentials', async () => {
  response(wrap(quote))
  expect((await getExternalQuote('external:AAPL')).data.price).toBe(102)
  expect(fetch).toHaveBeenCalledWith('/api/market/AAPL/quote', expect.objectContaining({ credentials: 'same-origin', headers: { Accept: 'application/json' } }))
})
it('rejects wrong-symbol quote responses', async () => {
  response(wrap({ ...quote, symbol: 'TSLA' }))
  await expect(getExternalQuote('external:AAPL')).rejects.toThrow('Invalid external quote')
})
it('preserves missing market values and zero session counts', async () => {
  response(wrap({ ...quote, price: null, priceAsOf: null, sessionTradeCount: 0 }))
  expect((await getExternalQuote('external:AAPL')).data.price).toBeNull()
})
it('surfaces configuration and provider failures', async () => {
  response({ error: 'Alpaca credentials need checking.' }, 503)
  await expect(getExternalQuote('external:AAPL')).rejects.toThrow('Alpaca credentials need checking.')
})
it('validates historical order and OHLC rather than sorting malformed bars', async () => {
  const bar = { startTime: '2026-10-30T19:00:00Z', open: 100, high: 103, low: 99, close: 102, volume: 20, tradeCount: 2 }
  response(wrap({ symbol: 'AAPL', range: '1M', interval: '1Hour', delayMinutes: 15, adjustment: 'split', candles: [bar, bar] }))
  await expect(getExternalHistory('history:AAPL:1M')).rejects.toThrow('Invalid external chart')
})
it('accepts a configured-independent watchlist and empty provider history', async () => {
  response({ provider: 'Alpaca', feed: 'IEX', configured: false, instruments: [{ symbol: 'AAPL', companyName: 'Apple Inc.', currency: 'USD' }] })
  expect((await getMarketConnection()).configured).toBe(false)
  response(wrap({ symbol: 'AAPL', range: '1D', interval: '1Min', delayMinutes: 15, adjustment: 'split', candles: [] }))
  expect((await getExternalHistory('history:AAPL:1D')).data.candles).toEqual([])
})

it.each([['1D', '1Min'], ['1M', '1Hour'], ['1Y', '1Day']])('accepts the correct provider resolution for %s', async (range, interval) => {
  response(wrap({ symbol: 'AAPL', range, interval, delayMinutes: 15, adjustment: 'split', candles: [] }))
  expect((await getExternalHistory(`history:AAPL:${range}`)).data.interval).toBe(interval)
})
