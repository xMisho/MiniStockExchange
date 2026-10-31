import { useEffect, useState } from 'react'

type Result<T> = { symbol: string; attempt: number } & ({ status: 'ready'; data: T } | { status: 'error' })
type Resource<T> = { status: 'loading' } | { status: 'error' } | { status: 'ready'; data: T }

export function useSymbolResource<T>(symbol: string, load: (symbol: string, signal: AbortSignal) => Promise<T>) {
  const [result, setResult] = useState<Result<T> | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    load(symbol, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setResult({ symbol, attempt, status: 'ready', data })
      })
      .catch(() => {
        if (!controller.signal.aborted) setResult({ symbol, attempt, status: 'error' })
      })
    return () => controller.abort()
  }, [symbol, attempt, load])

  const resource: Resource<T> = result?.symbol === symbol && result.attempt === attempt ? result : { status: 'loading' }
  return { resource, retry: () => setAttempt((value) => value + 1) }
}
