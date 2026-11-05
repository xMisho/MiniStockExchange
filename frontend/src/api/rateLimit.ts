// Share server-directed read cooldowns across panels. Mutations are never retried.
const cooldowns = new Map<string, number>()
export class RateLimitError extends Error {
  retryAfterSeconds: number
  constructor(retryAfterSeconds: number) {
    super(`Too many requests. Wait ${retryAfterSeconds} seconds before trying again.`)
    this.retryAfterSeconds = retryAfterSeconds
  }
}
export async function rateLimitedFetch(path: string, init: RequestInit): Promise<Response> {
  const read = (init.method ?? 'GET') === 'GET'
  const key = path.startsWith('/api/market/') ? 'external' : 'reads'
  if (read) {
    const remaining = Math.ceil(((cooldowns.get(key) ?? 0) - Date.now()) / 1000)
    if (remaining > 0) throw new RateLimitError(remaining)
  }
  const response = await fetch(path, init)
  if (read && response.status === 429) {
    const seconds = Number(response.headers.get('Retry-After'))
    const delay = Number.isFinite(seconds) && seconds > 0 ? Math.min(3600, Math.ceil(seconds)) : 60
    cooldowns.set(key, Date.now() + delay * 1000)
    throw new RateLimitError(delay)
  }
  return response
}
