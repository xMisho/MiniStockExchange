export async function checkBackendHealth(signal?: AbortSignal): Promise<void> {
  const response = await fetch('/api/health', {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    signal,
  })

  if (!response.ok) {
    throw new Error('Backend unavailable. Start Spring Boot and try again.')
  }

  const data: unknown = await response.json()
  if (typeof data !== 'object' || data === null || !('status' in data) || data.status !== 'UP') {
    throw new Error('The backend returned an unexpected health response.')
  }
}
