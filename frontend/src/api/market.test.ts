import { afterEach, describe, expect, it, vi } from 'vitest'
import { getJson } from './client'
import { getStocks } from './stocks'
import { getMarketSnapshot } from './marketData'

const emptySnapshot = {
  symbol: 'XYZ', lastTradedPrice: null, priceChange: null, priceChangePercent: null,
  bestBidPrice: null, bestAskPrice: null, spread: null, volume: 0, tradeCount: 0, openOrderCount: 0, vwap: null,
}

function respond(data: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(data), { status }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => vi.unstubAllGlobals())

describe('market API', () => {
  it('loads stocks in server order through a relative same-origin request', async () => {
    const stocks = [{ symbol: 'XYZ', companyName: 'Example', referencePrice: 10 }]
    const fetchMock = respond(stocks)
    expect(await getStocks()).toEqual(stocks)
    expect(fetchMock).toHaveBeenCalledWith('/api/stocks', expect.objectContaining({ credentials: 'same-origin' }))
  })

  it('preserves null values and zero counters', async () => {
    respond(emptySnapshot)
    expect(await getMarketSnapshot('XYZ')).toEqual(emptySnapshot)
  })

  it('encodes the selected symbol in the snapshot path', async () => {
    const fetchMock = respond({ ...emptySnapshot, symbol: 'X/Y' })
    await getMarketSnapshot('X/Y')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/stocks/X%2FY/snapshot')
  })

  it.each([null, {}, [{ symbol: 'X', companyName: 'Example', referencePrice: '10' }],
    [{ symbol: 'X', companyName: 'Example', referencePrice: 1 }, { symbol: 'X', companyName: 'Other', referencePrice: 2 }]])('rejects invalid stock data: %j', async (payload) => {
    respond(payload)
    await expect(getStocks()).rejects.toThrow()
  })

  it.each([{ ...emptySnapshot, symbol: 'OTHER' }, { ...emptySnapshot, volume: -1 },
    { ...emptySnapshot, tradeCount: 0.5 }, { ...emptySnapshot, vwap: '10.00' },
    { ...emptySnapshot, volume: Number.MAX_SAFE_INTEGER + 1 }, {}])('rejects invalid or mismatched snapshots: %j', async (payload) => {
    respond(payload)
    await expect(getMarketSnapshot('XYZ')).rejects.toThrow('response is invalid')
  })

  it('rejects failed HTTP and malformed JSON responses', async () => {
    respond({}, 503)
    await expect(getStocks()).rejects.toThrow('503')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not json')))
    await expect(getStocks()).rejects.toThrow()
  })

  it('propagates cancellation and timeout to fetch', async () => {
    const controller = new AbortController()
    controller.abort()
    const fetchMock = vi.fn().mockImplementation((_url: string, options: RequestInit) => options.signal?.throwIfAborted())
    vi.stubGlobal('fetch', fetchMock)
    await expect(getJson('/api/stocks', controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
  })
})
