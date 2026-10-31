import { useState } from 'react'
import type { FormEvent } from 'react'
import { orderQuantity, priceJson } from '../../api/orders'
import { isOpenLimit } from '../../types/orders'
import type { Order } from '../../types/orders'

interface Props { order: Order; disabled: boolean; onAmend: (order: Order, price: string, remaining: string) => Promise<boolean>; onCancel: (order: Order) => Promise<boolean> }
export function OrderActions({ order, disabled, onAmend, onCancel }: Props) {
  const [editing, setEditing] = useState(false)
  const [price, setPrice] = useState('')
  const [remaining, setRemaining] = useState('')
  const [error, setError] = useState<string | null>(null)
  if (!isOpenLimit(order)) return <span className="order-terminal">—</span>
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (disabled) return
    setError(null)
    try { priceJson(price); orderQuantity(remaining) }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Check the amendment values.'); return }
    if (await onAmend(order, price, remaining)) setEditing(false)
  }
  return <div className="order-actions">
    <button type="button" disabled={disabled} aria-expanded={editing} onClick={() => { setEditing(!editing); setError(null) }}>Amend #{order.id}</button>
    <button type="button" disabled={disabled} onClick={() => { void onCancel(order) }}>Cancel #{order.id}</button>
    {editing && <form aria-label={`Amend order ${order.id}`} className="amend-form" onSubmit={(event) => { void submit(event) }}>
      <fieldset disabled={disabled}>
        <label>New price<input required inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} /></label>
        <label>New remaining quantity<input required inputMode="numeric" value={remaining} onChange={(event) => setRemaining(event.target.value)} /></label>
        <p className="panel-footnote">Use the quantity still wanted, not the original total. Increasing it or changing price loses FIFO priority.</p>
        {error && <p role="alert" className="trading-error">{error}</p>}
        <button type="submit" className="primary-button">Save amendment #{order.id}</button>
      </fieldset>
    </form>}
  </div>
}
