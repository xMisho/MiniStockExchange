import { useEffect, useState } from 'react'
import { getOrderEvents } from '../../api/orders'
import type { OrderEvent } from '../../api/orders'
import type { Order } from '../../types/orders'
import { formatOrderPrice, formatUtcDateTime } from '../../utils/format'

export function OrderLifecycle({ order, expanded = false }: { order: Order; expanded?: boolean }) {
  const [open, setOpen] = useState(expanded)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{ key: string; events?: OrderEvent[]; error?: string } | null>(null)
  const key = `${order.id}:${order.updatedAt}:${order.status}:${order.filledQuantity}:${attempt}`
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    getOrderEvents(order.id, controller.signal).then(events => {
      if (!controller.signal.aborted) setState({ key, events })
    }).catch(() => { if (!controller.signal.aborted) setState({ key, error: 'Couldn’t load order activity.' }) })
    return () => controller.abort()
  }, [open, order.id, key])
  const current = state?.key === key ? state : null
  const content = open && <div className="activity-content"><p>Submitted {formatUtcDateTime(order.createdAt)} · Original {order.originalQuantity} shares</p>
      <p><strong>{order.status.replaceAll('_', ' ')}</strong> · Filled {order.filledQuantity} · Remaining {order.remainingQuantity}</p>
      <p className="panel-footnote">Latest 100 events, newest first. Detailed actions are recorded from this upgrade onward; earlier amendments cannot be reconstructed.</p>
      {!current ? <p role="status">Loading activity…</p> : current.error ? <p role="alert">{current.error} <button type="button" className="text-button" onClick={() => setAttempt(value => value + 1)}>Retry activity</button></p>
        : <ol>{current.events?.map(event => <li key={`${event.kind}:${event.id}`}><strong>{event.kind}</strong> · {formatUtcDateTime(event.occurred_at)}<br />
          {event.kind === 'FILL' ? `${event.quantity} shares at ${formatOrderPrice(event.price)}` : event.kind === 'AMENDED' ? `Limit ${formatOrderPrice(event.previous_price)} → ${formatOrderPrice(event.price)}; requested remaining ${event.previous_remaining} → ${event.quantity}; resulting remaining ${event.remaining_quantity}` : `${event.quantity} shares; remaining ${event.remaining_quantity}`}
        </li>)}</ol>}
    </div>
  return expanded ? <section className="order-lifecycle" aria-label={`Order ${order.id} activity`}>{content}</section> : <details className="order-lifecycle" onToggle={event => setOpen(event.currentTarget.open)}><summary>Order activity</summary>{content}</details>
}
