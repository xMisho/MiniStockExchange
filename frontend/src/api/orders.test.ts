import { afterEach, expect, it, vi } from 'vitest'
import { amendOrder, cancelOrder, getMyOrders, orderQuantity, priceJson, submitOrder } from './orders'
import { onSessionExpired } from './auth'
const csrf = { token: 'fresh-token', headerName: 'X-CSRF-TOKEN' }
const order = { id: 12, userId: 1, symbol: 'AAPL', side: 'BUY', type: 'LIMIT', limitPrice: 100, originalQuantity: 5, filledQuantity: 0, remainingQuantity: 5, status: 'OPEN', createdAt: '2026-10-31T12:00:00Z', updatedAt: '2026-10-31T12:00:00Z' }
function reply(body: unknown, status = 200) { return new Response(status === 204 ? null : JSON.stringify(body), { status }) }
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
it('submits exact numeric decimal input, never floating-point rounded prices', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply(order, 201))
  vi.stubGlobal('fetch', fetcher)
  expect(await submitOrder('AAPL', { side: 'BUY', type: 'LIMIT', quantity: 5, limitPrice: '999999999999999.9999' })).toEqual(order)
  expect(fetcher.mock.calls[0][0]).toBe('/api/auth/csrf')
  const [path, init] = fetcher.mock.calls[1]
  expect(path).toBe('/api/stocks/AAPL/orders')
  expect(init.body).toContain('"limitPrice":999999999999999.9999')
  expect(init.headers['X-CSRF-TOKEN']).toBe('fresh-token')
  expect(init.credentials).toBe('same-origin')
})
it('omits price entirely for market orders', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply({ ...order, type: 'MARKET', limitPrice: null, status: 'CANCELLED' }, 201))
  vi.stubGlobal('fetch', fetcher)
  await submitOrder('AAPL', { side: 'BUY', type: 'MARKET', quantity: 5, limitPrice: '100' })
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ side: 'BUY', type: 'MARKET', quantity: 5 })
})
it.each([400, 409, 403])('preserves a definite backend rejection (%s)', async (status) => {
  const fetcher = vi.fn().mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply({ error: 'Order rejected.' }, status))
  vi.stubGlobal('fetch', fetcher)
  await expect(cancelOrder(12)).rejects.toMatchObject({ message: 'Order rejected.', status, uncertain: false })
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('preserves uncertain 503 metadata and does not retry', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(reply({ error: 'Check your history.', code: 'TRADING_OUTCOME_UNCERTAIN', retrySafe: false }, 503))
  vi.stubGlobal('fetch', fetcher)
  await expect(cancelOrder(12)).rejects.toMatchObject({ uncertain: true, status: 503 })
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('treats lost mutation responses as uncertain without retrying', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(reply(csrf)).mockRejectedValueOnce(new TypeError('Network lost'))
  vi.stubGlobal('fetch', fetcher)
  await expect(amendOrder(12, '100.25', '3')).rejects.toMatchObject({ uncertain: true })
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('does not call a mutation when CSRF preparation fails', async () => {
  const fetcher = vi.fn().mockRejectedValue(new TypeError('Network lost'))
  vi.stubGlobal('fetch', fetcher)
  await expect(cancelOrder(12)).rejects.toMatchObject({ uncertain: false })
  expect(fetcher).toHaveBeenCalledTimes(1)
})
it('treats unreadable success responses as uncertain', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(reply(csrf)).mockResolvedValueOnce(new Response('not-json', { status: 201 })))
  await expect(submitOrder('AAPL', { side: 'BUY', type: 'LIMIT', quantity: 5, limitPrice: '100' })).rejects.toMatchObject({ uncertain: true })
})
it('requests history with server-side page and status filtering and supports cancellation', async () => {
  const controller = new AbortController()
  const fetcher = vi.fn().mockResolvedValue(reply({ content: [order], page: 2, size: 25, totalElements: 51, totalPages: 3 }))
  vi.stubGlobal('fetch', fetcher)
  const result = await getMyOrders(2, 'OPEN', controller.signal)
  expect(fetcher.mock.calls[0][0]).toBe('/api/orders/me?page=2&size=25&status=OPEN')
  expect(result.content[0].id).toBe(12)
  const signal = fetcher.mock.calls[0][1].signal as AbortSignal
  controller.abort()
  expect(signal.aborted).toBe(true)
})
it('resets sessions on protected order-history 401', async () => {
  const expired = vi.fn(); const unsubscribe = onSessionExpired(expired)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ error: 'Authentication required.' }, 401)))
  await expect(getMyOrders(0, '')).rejects.toThrow('Authentication required.')
  expect(expired).toHaveBeenCalledOnce()
  unsubscribe()
})
it('rejects unsafe IDs or malformed history', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ content: [{ ...order, id: Number.MAX_SAFE_INTEGER + 1 }], page: 0, size: 25, totalElements: 1, totalPages: 1 })))
  await expect(getMyOrders(0, '')).rejects.toThrow('invalid')
})
it('uses PATCH and DELETE with fresh CSRF and no automatic replay', async () => {
  const fetcher = vi.fn().mockImplementation(async (path) => path === '/api/auth/csrf' ? reply(csrf) : reply(null, 204))
  vi.stubGlobal('fetch', fetcher)
  await amendOrder(12, '00100.2500', '3')
  await cancelOrder(12)
  expect(fetcher.mock.calls[1][1].method).toBe('PATCH')
  expect(fetcher.mock.calls[1][1].body).toBe('{"newPrice":100.2500,"newRemainingQuantity":3}')
  expect(fetcher.mock.calls[3][1].method).toBe('DELETE')
  expect(fetcher).toHaveBeenCalledTimes(4)
})
it('validates price and quantity without rounding or truncation', () => {
  expect(priceJson('000.0001')).toBe('0.0001')
  for (const value of ['0', '-1', '1e2', '1.00001', '1,000', '1000000000000000', '1}bad']) expect(() => priceJson(value)).toThrow()
  expect(orderQuantity('2147483647')).toBe(2147483647)
  for (const value of ['0', '-1', '1.5', '1e2', '2147483648']) expect(() => orderQuantity(value)).toThrow()
})
