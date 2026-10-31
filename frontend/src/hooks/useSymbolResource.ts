import { useEffect, useRef, useState } from 'react'

type Resource<T> = { status: 'loading' } | { status: 'error' } | { status: 'ready'; data: T }
type Result<T> = { symbol: string; resource: Resource<T>; updatedAt: number | null; refreshError: boolean; refreshing: boolean }

export function useSymbolResource<T>(symbol: string | null, load: (symbol: string, signal: AbortSignal) => Promise<T>, interval = 2000) {
  const [result, setResult] = useState<Result<T> | null>(null)
  const retryRef = useRef<() => void>(() => {})

  useEffect(() => {
    if (symbol === null) return
    let disposed = false
    let controller: AbortController | null = null
    let lastRequest: AbortController | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    let cached: T | undefined
    let updatedAt: number | null = null
    const publish = (resource: Resource<T>, refreshError = false, refreshing = false) => {
      if (!disposed) setResult({ symbol, resource, updatedAt, refreshError, refreshing })
    }
    const visible = () => document.visibilityState !== 'hidden'
    const run = async () => {
      if (disposed || !visible() || controller) return
      clearTimeout(timer)
      const request = new AbortController()
      controller = request
      lastRequest = request
      publish(cached === undefined ? { status: 'loading' } : { status: 'ready', data: cached }, false, true)
      try {
        const data = await load(symbol, request.signal)
        if (disposed || request.signal.aborted) return
        cached = data
        updatedAt = Date.now()
        publish({ status: 'ready', data })
      } catch {
        if (disposed || request.signal.aborted) return
        publish(cached === undefined ? { status: 'error' } : { status: 'ready', data: cached }, true)
      } finally {
        if (controller === request) {
          controller = null
          if (!disposed && visible()) timer = setTimeout(() => { void run() }, interval)
        }
      }
    }
    const visibilityChanged = () => {
      clearTimeout(timer)
      if (document.visibilityState === 'hidden') {
        controller?.abort()
        controller = null
      } else void run()
    }
    retryRef.current = () => { void run() }
    document.addEventListener('visibilitychange', visibilityChanged)
    void run()
    return () => {
      disposed = true
      clearTimeout(timer)
      lastRequest?.abort()
      document.removeEventListener('visibilitychange', visibilityChanged)
      retryRef.current = () => {}
    }
  }, [symbol, load, interval])

  const current = result?.symbol === symbol ? result : null
  return {
    resource: current?.resource ?? { status: 'loading' as const },
    updatedAt: current?.updatedAt ?? null,
    refreshError: current?.refreshError ?? false,
    refreshing: current?.refreshing ?? false,
    retry: () => retryRef.current(),
  }
}
