import { afterEach, expect, it, vi } from 'vitest'
import { getCurrentUser, login, logout, register, csrfRequest, onSessionExpired } from './auth'
const user = { id: 1, username: 'example', email: 'example@example.test', role: 'USER' }
const token = { token: 'fresh-token', headerName: 'X-CSRF-TOKEN' }
function response(body: unknown, status = 200) { return new Response(status === 204 ? null : JSON.stringify(body), { status }) }
afterEach(() => vi.unstubAllGlobals())
it('gets CSRF before form login and preserves password characters exactly', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(token)).mockResolvedValueOnce(response(user))
  vi.stubGlobal('fetch', fetcher)
  expect(await login('example', ' a&b+= password ')).toEqual(user)
  expect(fetcher.mock.calls[0][0]).toBe('/api/auth/csrf')
  const [path, init] = fetcher.mock.calls[1]
  expect(path).toBe('/api/auth/login')
  expect(new URLSearchParams(init.body).get('password')).toBe(' a&b+= password ')
  expect(init.headers['X-CSRF-TOKEN']).toBe('fresh-token')
  expect(init.credentials).toBe('same-origin')
})
it('registers JSON without any client-supplied role', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(token)).mockResolvedValueOnce(response(user, 201))
  vi.stubGlobal('fetch', fetcher)
  await register('example', 'example@example.test', 'ExamplePassword123!')
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ username: 'example', email: 'example@example.test', password: 'ExamplePassword123!' })
})
it.each([[409, 'Username already exists.'], [400, 'Password must contain 15–128 characters.'], [401, 'Invalid username or password.'], [403, 'Access denied or invalid CSRF token.']])('surfaces backend errors without retrying mutations (%s)', async (status, error) => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(token)).mockResolvedValueOnce(response({ error }, status))
  vi.stubGlobal('fetch', fetcher)
  await expect(login('example', 'password')).rejects.toThrow(error)
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('treats anonymous /me as normal but does not hide server outages', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 401)))
  expect(await getCurrentUser()).toBeNull()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 503)))
  await expect(getCurrentUser()).rejects.toThrow()
})
it('does not submit without a valid CSRF response', async () => {
  const fetcher = vi.fn().mockResolvedValue(response({ token: '', headerName: 'X-CSRF-TOKEN' }))
  vi.stubGlobal('fetch', fetcher)
  await expect(logout()).rejects.toThrow('security token')
  expect(fetcher).toHaveBeenCalledTimes(1)
})
it('signals expired sessions for protected requests, not bad login', async () => {
  const expired = vi.fn()
  const unsubscribe = onSessionExpired(expired)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (path) => path === '/api/auth/csrf' ? response(token) : response({}, 401)))
  await expect(csrfRequest('/api/orders/me', { method: 'GET' })).rejects.toThrow()
  expect(expired).toHaveBeenCalledTimes(1)
  await expect(login('bad', 'bad')).rejects.toThrow()
  expect(expired).toHaveBeenCalledTimes(1)
  unsubscribe()
})
