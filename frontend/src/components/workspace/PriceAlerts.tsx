import { useCallback, useEffect, useRef, useState } from 'react'
import { EmailPreferences } from './EmailPreferences'
import { createPortal } from 'react-dom'
import { createAlert, getAlerts, removeAlert } from '../../api/workspace'
import { useAuth } from '../../auth/authState'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import { SymbolSearch } from './SymbolSearch'
import type { Instrument } from '../../types/externalMarket'
import { priceJson } from '../../api/orders'
import { formatPrice, formatUtcDateTime } from '../../utils/format'
export function PriceAlerts({ symbol, instruments = [] }: { symbol: string; instruments?: Instrument[] }) {
  const auth = useAuth()
  const [alertSymbol, setAlertSymbol] = useState(symbol)
  const [searched, setSearched] = useState<Instrument | null>(null)
  const choices = [...new Map([
    { symbol: alertSymbol, companyName: alertSymbol, currency: 'USD' as const },
    { symbol: 'AAPL', companyName: 'Apple Inc.', currency: 'USD' as const },
    { symbol: 'TSLA', companyName: 'Tesla, Inc.', currency: 'USD' as const },
    ...instruments, ...(searched ? [searched] : []),
  ].map(item => [item.symbol, item])).values()].sort((a, b) => a.symbol.localeCompare(b.symbol))
  const [toast, setToast] = useState<string | null>(null)
  const announced = useRef(new Set<number>())
  const load = useCallback(async (key: string, signal: AbortSignal) => {
    const result = await getAlerts(key, signal)
    if (!signal.aborted) {
      const fresh = result.alerts.filter(alert => alert.triggeredAt !== null && alert.triggeredPrice !== null && result.newlyTriggeredIds?.includes(alert.id) && !announced.current.has(alert.id))
      if (fresh.length) {
        fresh.forEach(alert => announced.current.add(alert.id))
        setToast(fresh.map(alert => `${alert.symbol} crossed ${alert.direction.toLowerCase()} ${formatPrice(alert.target)} at ${formatPrice(alert.triggeredPrice)}.`).join(' '))
      }
    }
    return result
  }, [])
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 10000)
    return () => clearTimeout(timer)
  }, [toast])
  const state = useSymbolResource(auth.user ? `alerts:${auth.user.id}` : null, load, 15000)
  const [direction, setDirection] = useState('ABOVE'), [target, setTarget] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const data = state.resource.status === 'ready' ? state.resource.data : null
  async function change(action: () => Promise<void>) {
    if (busy || auth.busy) return
    setBusy(true); setError(null); const release = auth.holdSession()
    try { await action(); state.retry() } catch (e) { setError(e instanceof Error ? e.message : 'Alert update failed.'); state.retry() } finally { release(); setBusy(false) }
  }
  const notification = toast && <div className="trading-notice success action-toast price-alert-toast" role="status" aria-live="polite" aria-atomic="true"><p><strong>Price alert triggered</strong><br />{toast}</p><button className="text-button" type="button" aria-label="Dismiss price alert notification" onClick={() => setToast(null)}>Dismiss</button></div>
  const feedback = document.getElementById('trading-feedback')
  return <><section className="data-panel" aria-label="Price alerts"><div className="data-heading"><h2>Price alerts</h2><span>Alpaca · IEX</span></div>
    {!auth.user ? <p className="panel-message">Sign in to create in-app price alerts.</p> : <>
      <SymbolSearch label="Find alert stock" regionLabel="Search alert stocks" disabled={busy || auth.busy} onSelect={item => { setSearched(item); setAlertSymbol(item.symbol); setTarget(''); setError(null) }} />
      <form className="tool-form" onSubmit={event => { event.preventDefault(); void change(async () => { const price = priceJson(target); await createAlert(alertSymbol, direction, price); setTarget('') }) }}>
        <label>Alert stock<select value={alertSymbol} onChange={event => { setAlertSymbol(event.target.value); setTarget(''); setError(null) }} disabled={busy || auth.busy}>{choices.map(item => <option key={item.symbol} value={item.symbol}>{item.symbol} · {item.companyName}</option>)}</select></label><label>Crossing<select value={direction} onChange={e => setDirection(e.target.value)} disabled={busy}><option value="ABOVE">Above</option><option value="BELOW">Below</option></select></label><label>Target price<input required value={target} onChange={e => setTarget(e.target.value)} inputMode="decimal" disabled={busy} /></label><button className="primary-button" disabled={busy || auth.busy}>{busy ? 'Saving…' : 'Create alert'}</button>
      </form>
      <p className="panel-footnote">Creating an alert for <strong>{alertSymbol}</strong> · External market · IEX · USD. Choose Apple/Tesla, a watchlist stock, or search any supported symbol above. Alert selection is independent of the chart.</p>
      {(error || state.errorMessage) && <p role="alert">{error ?? state.errorMessage}</p>}{data?.warning && <p className="source-warning">{data.warning}</p>}
      {data?.alerts.length === 0 && <p className="panel-message">No alerts yet.</p>}
      <ul className="alert-list">{data?.alerts.map(alert => <li key={alert.id}><div><span className={`alert-badge ${alert.triggeredAt ? 'triggered' : 'armed'}`}>{alert.triggeredAt ? 'Triggered' : 'Armed'}</span><strong>{alert.symbol} {alert.direction.toLowerCase()} {formatPrice(alert.target)}</strong><p>{alert.triggeredAt ? `Triggered at ${formatPrice(alert.triggeredPrice)} · ${formatUtcDateTime(alert.triggeredAt)}` : `Armed · Last observed ${alert.lastObservedAt ? formatUtcDateTime(alert.lastObservedAt) : 'not yet'}`}</p></div><button className="text-button" disabled={busy || auth.busy} onClick={() => { void change(() => removeAlert(alert.id)) }} aria-label={`Delete alert ${alert.id}`}>Delete</button></li>)}</ul>
    </>}
    <EmailPreferences key={auth.user?.id ?? 'anonymous'} /><p className="panel-footnote">Alerts start from the price observed when armed; they do not fire merely because a threshold was already passed. Prices between samples can be missed. Verified users can opt into email notifications above.</p>
  </section>{notification && (feedback ? createPortal(notification, feedback) : notification)}</>
}
