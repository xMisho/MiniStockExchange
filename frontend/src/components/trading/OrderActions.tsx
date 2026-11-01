import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { orderQuantity, priceJson } from '../../api/orders'
import { isOpenLimit } from '../../types/orders'
import type { Order } from '../../types/orders'
import { Modal } from '../Modal'

interface Props { order: Order; disabled: boolean; pending: boolean; feedback?: { message: string; uncertain: boolean }; onAmend: (order: Order, price: string, remaining: string) => Promise<boolean>; onCancel: (order: Order) => Promise<boolean> }
export function OrderActions({ order, disabled, pending, feedback, onAmend, onCancel }: Props) {
  const [editing, setEditing] = useState(false)
  const [failed, setFailed] = useState(false)
  const [price, setPrice] = useState('')
  const [remaining, setRemaining] = useState('')
  const [error, setError] = useState<{ field: 'price' | 'quantity'; message: string } | null>(null)
  const priceInput = useRef<HTMLInputElement>(null)
  const remainingInput = useRef<HTMLInputElement>(null)
  if (!isOpenLimit(order)) return <span className="order-terminal">No actions</span>
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (disabled) return
    setError(null); setFailed(false)
    try { priceJson(price) }
    catch (failure) { setError({ field: 'price', message: failure instanceof Error ? failure.message : 'Check the price.' }); priceInput.current?.focus(); return }
    try { orderQuantity(remaining) }
    catch (failure) { setError({ field: 'quantity', message: failure instanceof Error ? failure.message : 'Check the quantity.' }); remainingInput.current?.focus(); return }
    if (await onAmend(order, price, remaining)) setEditing(false)
    else setFailed(true)
  }
  return <div className="order-actions">
    <button type="button" className="text-button" disabled={disabled} aria-haspopup="dialog" aria-label={`Amend #${order.id}`} onClick={() => { setEditing(true); setPrice(''); setRemaining(''); setError(null); setFailed(false) }}>Amend</button>
    <button type="button" className="text-button cancel-button" disabled={disabled} aria-label={`Cancel #${order.id}`} onClick={() => { void onCancel(order) }}>Cancel</button>
    {editing && <Modal title={`Amend order #${order.id}`} description={`${order.symbol} · ${order.side} LIMIT. Enter the new price and shares you still want outstanding.`} busy={pending} onClose={() => setEditing(false)}>
      {failed && feedback && <div className="trading-error" role="alert"><p>{feedback.message}</p>{feedback.uncertain && <p>Do not resubmit blindly. Close this dialog and check My Orders.</p>}</div>}
      <form aria-label={`Amend order ${order.id}`} className="amend-form" onSubmit={(event) => { void submit(event) }}>
        <fieldset disabled={disabled}>
          <label>New price<input ref={priceInput} required inputMode="decimal" aria-invalid={error?.field === 'price'} aria-describedby={error?.field === 'price' ? `amend-error-${order.id}` : undefined} value={price} onChange={(event) => { setPrice(event.target.value); if (error?.field === 'price') setError(null) }} /></label>
          <label>New remaining quantity<input ref={remainingInput} required inputMode="numeric" aria-invalid={error?.field === 'quantity'} aria-describedby={error?.field === 'quantity' ? `amend-error-${order.id}` : undefined} value={remaining} onChange={(event) => { setRemaining(event.target.value); if (error?.field === 'quantity') setError(null) }} /></label>
          <p className="panel-footnote">Increasing quantity or changing price moves your order to the back of its price queue.</p>
          {error && <p role="alert" className="trading-error" id={`amend-error-${order.id}`}>{error.message}</p>}
          <div className="form-actions"><button type="button" className="text-button" onClick={() => setEditing(false)}>Keep current order</button><button type="submit" className="primary-button" aria-label={`Save amendment #${order.id}`}>Save amendment</button></div>
        </fieldset>
      </form>
    </Modal>}
  </div>
}
