import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkBackendHealth } from './health'

afterEach(() => vi.unstubAllGlobals())

describe('backend health request', () => {
  it('uses the relative proxy route and same-origin cookies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' })))
    vi.stubGlobal('fetch', fetchMock)
    const signal = new AbortController().signal

    await expect(checkBackendHealth(signal)).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith('/api/health', {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      signal,
    })
  })

  it('rejects failed HTTP responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))
    await expect(checkBackendHealth()).rejects.toThrow('Backend unavailable')
  })

  it.each([null, {}, { status: 'DOWN' }, 'UP'])('rejects an invalid health payload: %j', async (payload) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload))))
    await expect(checkBackendHealth()).rejects.toThrow('unexpected health response')
  })

  it('propagates network failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network unavailable')))
    await expect(checkBackendHealth()).rejects.toThrow('Network unavailable')
  })

  it('supports cancellation of an obsolete request', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url, options: RequestInit) => {
      options.signal?.throwIfAborted()
    }))
    await expect(checkBackendHealth(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })
})
