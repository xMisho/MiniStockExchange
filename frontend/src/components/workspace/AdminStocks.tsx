import { useState } from 'react'
import { registerStock } from '../../api/workspace'
import { priceJson } from '../../api/orders'
import { useAuth } from '../../auth/authState'
export function AdminStocks({ onRegistered }: { onRegistered: () => void }) {
  const auth = useAuth()
  const [symbol, setSymbol] = useState(''), [name, setName] = useState(''), [price, setPrice] = useState(''), [busy, setBusy] = useState(false), [message, setMessage] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  if (auth.user?.role !== 'ADMIN') return null
  return <details className="data-panel admin-stocks"><summary>Admin · Register local stock</summary><form className="tool-form" onSubmit={e => { e.preventDefault(); if (busy || auth.busy) return; setBusy(true); setError(null); setMessage(null); const release = auth.holdSession(); void (async () => { try { await registerStock(symbol.trim().toUpperCase(), name.trim(), priceJson(price)); setMessage(`Registered ${symbol.trim().toUpperCase()} in the local exchange.`); setSymbol(''); setName(''); setPrice(''); onRegistered() } catch (failure) { setError(failure instanceof Error ? failure.message : 'Registration failed.'); onRegistered() } finally { setBusy(false); release() } })() }}>
    <label>Stock symbol<input required maxLength={20} value={symbol} disabled={busy} onChange={e => setSymbol(e.target.value)} /></label><label>Company name<input required maxLength={255} value={name} disabled={busy} onChange={e => setName(e.target.value)} /></label><label>Reference price<input required inputMode="decimal" value={price} disabled={busy} onChange={e => setPrice(e.target.value)} /></label><button className="primary-button" disabled={busy || auth.busy}>{busy ? 'Registering…' : 'Register stock'}</button>
  </form>{message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}<p className="panel-footnote">Creates a local instrument only. This does not list a security on a real exchange or change external market prices. If a request outcome is unknown, check the local stock list before retrying.</p></details>
}
