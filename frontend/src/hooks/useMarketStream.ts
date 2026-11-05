import { useEffect, useState } from 'react'
import { refreshMarket } from '../utils/marketEvents'

export function useMarketStream(symbol: string | null) {
  const [connected, setConnected] = useState(false)
  useEffect(() => {
    if (!symbol || typeof EventSource === 'undefined') return
    let stream: EventSource | null = null
    const connect = () => {
      stream?.close(); stream = null; setConnected(false)
      if (document.visibilityState === 'hidden') return
      stream = new EventSource('/api/events')
      stream.addEventListener('connected', () => { setConnected(true); refreshMarket(symbol) })
      stream.addEventListener('market', event => {
        try { if (JSON.parse((event as MessageEvent<string>).data).symbol === symbol) refreshMarket(symbol) } catch { /* Ignore malformed hints; polling remains active. */ }
      })
      stream.onerror = () => setConnected(false) // EventSource reconnects; regular polling remains active.
    }
    connect(); document.addEventListener('visibilitychange', connect)
    return () => { stream?.close(); document.removeEventListener('visibilitychange', connect) }
  }, [symbol])
  return connected
}
