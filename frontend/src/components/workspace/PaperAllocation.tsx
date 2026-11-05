import { useRef, useState } from 'react'
import { useAuth } from '../../auth/authState'
import { allocatePaperShares, orderQuantity } from '../../api/orders'
import { refreshMarket } from '../../utils/marketEvents'

export function PaperAllocation({ symbol }: { symbol: string | null }) {
  const auth = useAuth()
  const [recipient, setRecipient] = useState('')
  const [quantity, setQuantity] = useState('')
  const [busy, setBusy] = useState(false)
  const locked = useRef(false)
  const [message, setMessage] = useState('')
  if (auth.user?.role !== 'ADMIN' || !symbol) return null
  return <details className="data-panel admin-stocks"><summary>Admin · Allocate demo shares</summary>
    <p>Purchase {symbol} shares from the virtual treasury at this stock’s reference price using the recipient’s available virtual cash. Recorded with your admin ID. No real money.</p>
    <form className="trading-form" onSubmit={event => {
      event.preventDefault(); if (locked.current) return
      let id: number, count: number
      try { id = orderQuantity(recipient || String(auth.user?.id)); count = orderQuantity(quantity) } catch (error) { setMessage(error instanceof Error ? error.message : 'Check the fields.'); return }
      locked.current = true; setBusy(true); setMessage('')
      void allocatePaperShares(id, symbol, count).then(() => { setMessage(`Allocated ${count} ${symbol} shares to user #${id}.`); refreshMarket(symbol) })
        .catch(error => setMessage(`${error instanceof Error ? error.message : 'Allocation could not be confirmed.'} Check the recipient’s portfolio before trying again.`))
        .finally(() => { locked.current = false; setBusy(false) })
    }}><fieldset disabled={busy || auth.busy}><legend>Demo allocation</legend>
      <label>Recipient user ID<input inputMode="numeric" value={recipient} placeholder={String(auth.user.id)} onChange={event => setRecipient(event.target.value)} /></label>
      <label>Shares to allocate<input required inputMode="numeric" value={quantity} onChange={event => setQuantity(event.target.value)} /></label>
      <button type="submit" className="primary-button">{busy ? 'Allocating…' : 'Allocate demo shares'}</button>
    </fieldset></form><p role="status">{message}</p>
  </details>
}
