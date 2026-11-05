import { useEffect, useRef, useState } from 'react'
import { registerStock } from '../../api/workspace'
import { getExternalQuote, getMarketConnection } from '../../api/externalMarket'
import { priceJson } from '../../api/orders'
import { useAuth } from '../../auth/authState'
import { formatUtcDateTime } from '../../utils/format'
import type { Instrument } from '../../types/externalMarket'

export function AdminStocks({ onRegistered }: { onRegistered: () => void }) {
  const auth = useAuth()
  const [symbol, setSymbol] = useState(''), [name, setName] = useState(''), [price, setPrice] = useState('')
  const [busy, setBusy] = useState(false), [quoting, setQuoting] = useState(false)
  const [message, setMessage] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  const [instruments, setInstruments] = useState<Instrument[]>([])
  const [configured, setConfigured] = useState(false)
  const locked = useRef(false)
  const quoteRequest = useRef<AbortController | null>(null)
  useEffect(() => {
    if (auth.user?.role !== 'ADMIN') return
    const controller = new AbortController()
    getMarketConnection(controller.signal).then(data => {
      if (!controller.signal.aborted) { setInstruments(data.instruments); setConfigured(data.configured) }
    }).catch(() => { /* Manual local registration remains available. */ })
    return () => { controller.abort(); quoteRequest.current?.abort() }
  }, [auth.user?.role])
  if (auth.user?.role !== 'ADMIN') return null
  const disabled = busy || quoting || auth.busy
  return <details className="data-panel admin-stocks"><summary>Admin · Register local stock</summary>
    {instruments.length > 0 && <label className="stock-preset">Stock preset<select disabled={disabled} value={instruments.some(item => item.symbol === symbol) ? symbol : ''} onChange={event => {
      const item = instruments.find(item => item.symbol === event.target.value)
      if (item) { setSymbol(item.symbol); setName(item.companyName); setPrice(''); setMessage(null); setError(null) }
    }}><option value="">Custom stock / choose a preset</option>{instruments.map(item => <option key={item.symbol} value={item.symbol}>{item.symbol} · {item.companyName}</option>)}</select></label>}
    <form className="tool-form" onSubmit={event => {
      event.preventDefault(); if (locked.current || disabled) return
      locked.current = true; setBusy(true); setError(null); setMessage(null)
      const release = auth.holdSession()
      void (async () => {
        try { await registerStock(symbol.trim().toUpperCase(), name.trim(), priceJson(price)); setMessage(`Registered ${symbol.trim().toUpperCase()} in the local exchange.`); setSymbol(''); setName(''); setPrice(''); onRegistered() }
        catch (failure) { setError(failure instanceof Error ? failure.message : 'Registration failed.'); onRegistered() }
        finally { locked.current = false; setBusy(false); release() }
      })()
    }}>
      <label>Stock symbol<input required maxLength={20} value={symbol} disabled={disabled} onChange={event => { setSymbol(event.target.value); setMessage(null) }} /></label>
      <label>Company name<input required maxLength={255} value={name} disabled={disabled} onChange={event => setName(event.target.value)} /></label>
      <label>Reference price<input required inputMode="decimal" value={price} disabled={disabled} onChange={event => setPrice(event.target.value)} /></label>
      <button className="primary-button" disabled={disabled}>{busy ? 'Registering…' : 'Register stock'}</button>
    </form>
    {configured && <button type="button" className="text-button" disabled={disabled || !symbol.trim()} onClick={() => {
      if (quoting || busy) return
      const controller = new AbortController(); quoteRequest.current?.abort(); quoteRequest.current = controller
      setQuoting(true); setMessage(null); setError(null)
      void getExternalQuote(`external:${symbol.trim().toUpperCase()}`, controller.signal).then(result => {
        if (controller.signal.aborted) return
        if (result.stale || result.data.price === null || result.data.price <= 0) throw new Error('A fresh provider response with a reported trade price is required. Enter a reference price manually instead.')
        setPrice(priceJson(String(result.data.price)))
        setMessage(`Copied ${result.data.symbol} reported price${result.data.priceAsOf ? ` as of ${formatUtcDateTime(result.data.priceAsOf)}` : ''}. Review before registering; this becomes a fixed local reference.`)
      }).catch(failure => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Quote unavailable.') })
        .finally(() => { if (!controller.signal.aborted) setQuoting(false) })
    }}>{quoting ? 'Loading reported price…' : 'Use external reported price as reference'}</button>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    <p className="panel-footnote">Presets do not register stocks automatically. Reference price is a fixed local input; copying a provider price does not route local orders to Alpaca or update their execution prices. If registration is uncertain, check the local stock list before retrying.</p>
  </details>
}
