import { afterEach, expect, it, vi } from 'vitest'
import { rateLimitedFetch } from './rateLimit'
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
it('shares read cooldowns without refetching, then resumes after Retry-After', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2030-01-01'))
  const fetcher = vi.fn().mockResolvedValueOnce(new Response('{}', { status: 429, headers: { 'Retry-After': '10' } }))
    .mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  await expect(rateLimitedFetch('/api/market/AAPL/quote', {})).rejects.toThrow('10 seconds')
  await expect(rateLimitedFetch('/api/market/TSLA/quote', {})).rejects.toThrow('10 seconds')
  expect(fetcher).toHaveBeenCalledTimes(1)
  await rateLimitedFetch('/api/stocks', {})
  vi.advanceTimersByTime(10000)
  await rateLimitedFetch('/api/market/AAPL/quote', {})
  expect(fetcher).toHaveBeenCalledTimes(3)
})
it('returns a mutation rejection once without automatically retrying', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 429 }))
  vi.stubGlobal('fetch', fetcher)
  expect((await rateLimitedFetch('/api/stocks/AAPL/orders', { method: 'POST' })).status).toBe(429)
  expect(fetcher).toHaveBeenCalledTimes(1)
})
