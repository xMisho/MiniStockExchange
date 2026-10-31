export async function getJson(path: string, signal?: AbortSignal): Promise<unknown> {
  const timeout = AbortSignal.timeout(8_000)
  const response = await fetch(path, {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })

  if (!response.ok) throw new Error(`Market request failed (${response.status}).`)
  return response.json()
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}
