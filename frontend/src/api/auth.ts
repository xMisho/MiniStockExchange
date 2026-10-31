import { isRecord } from './client'

export interface User { id: number; username: string; email: string; role: 'USER' | 'ADMIN' }
export class AuthError extends Error {
  status: number
  mutationSent: boolean
  code?: string
  retrySafe?: boolean
  constructor(message: string, status: number, mutationSent = false, code?: string, retrySafe?: boolean) {
    super(message); this.status = status; this.mutationSent = mutationSent; this.code = code; this.retrySafe = retrySafe
  }
}
const expiredListeners = new Set<() => void>()
export function onSessionExpired(listener: () => void) { expiredListeners.add(listener); return () => { expiredListeners.delete(listener) } }

async function request(path: string, init: RequestInit = {}, protectedRequest = false): Promise<Response> {
  let response: Response
  const mutationSent = !['GET', 'HEAD'].includes(init.method ?? 'GET')
  const timeout = AbortSignal.timeout(8000)
  try {
    response = await fetch(path, { ...init, credentials: 'same-origin', signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout, headers: { Accept: 'application/json', ...init.headers } })
  } catch { throw new AuthError(mutationSent ? 'Couldn’t reach the server. The request outcome may be unknown; check before trying again.' : 'Couldn’t reach the server. Check your connection and try again.', 0, mutationSent) }
  if (!response.ok) {
    if (init.signal?.aborted) throw new DOMException('Request aborted', 'AbortError')
    if (response.status === 401 && protectedRequest) expiredListeners.forEach((listener) => listener())
    let code: string | undefined
    let retrySafe: boolean | undefined
    let message = response.status === 403 ? 'Access denied or security token expired. Please try again.' : `Request failed (${response.status}).`
    try { const body: unknown = await response.json(); if (isRecord(body)) { if (typeof body.error === 'string') message = body.error; if (typeof body.code === 'string') code = body.code; if (typeof body.retrySafe === 'boolean') retrySafe = body.retrySafe } } catch {}
    throw new AuthError(message, response.status, mutationSent, code, retrySafe)
  }
  return response
}
function userFrom(value: unknown): User {
  if (!isRecord(value) || !Number.isSafeInteger(value.id) || (value.id as number) <= 0 || typeof value.username !== 'string'
    || typeof value.email !== 'string' || (value.role !== 'USER' && value.role !== 'ADMIN')) throw new Error('The account response is invalid. Refresh to check your session.')
  return { id: value.id as number, username: value.username, email: value.email, role: value.role }
}
export async function refreshCsrf() {
  const value: unknown = await (await request('/api/auth/csrf')).json()
  if (!isRecord(value) || typeof value.token !== 'string' || !value.token || value.headerName !== 'X-CSRF-TOKEN') throw new Error('Couldn’t obtain a valid security token. Please try again.')
  return { token: value.token, headerName: value.headerName }
}
export async function csrfRequest(path: string, init: RequestInit, protectedRequest = true) {
  const csrf = await refreshCsrf()
  return request(path, { ...init, headers: { ...init.headers, [csrf.headerName]: csrf.token } }, protectedRequest)
}
export async function getCurrentUser(): Promise<User | null> {
  try { return userFrom(await (await request('/api/auth/me')).json()) }
  catch (error) { if (error instanceof AuthError && error.status === 401) return null; throw error }
}
export async function login(username: string, password: string) {
  return userFrom(await (await csrfRequest('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username, password }).toString() }, false)).json())
}
export async function register(username: string, email: string, password: string) {
  return userFrom(await (await csrfRequest('/api/users', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, email, password }) }, false)).json())
}
export async function logout() { await csrfRequest('/api/auth/logout', { method: 'POST' }) }

export function authenticatedRequest(path: string, signal?: AbortSignal) { return request(path, { signal }, true) }
