import { useState } from 'react'
import type { FormEvent } from 'react'
import { orderQuantity, priceJson } from '../../api/orders'
import type { OrderSide, OrderType, SubmitOrder } from '../../types/orders'

interface Props { symbol: string; disabled: boolean; onSubmit: (symbol: string, order: SubmitOrder) => Promise<boolean> }
export function OrderForm({ symbol, disabled, onSubmit }: Props) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [type, setType] = useState<OrderType>('LIMIT')
  const [quantity, setQuantity] = useState('')
  const [price, setPrice] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (disabled) return
    setError(null)
    let order: SubmitOrder
    try { order = { side, type, quantity: orderQuantity(quantity), ...(type === 'LIMIT' ? { limitPrice: priceJson(price) } : {}) } }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Check the order values.'); return }
    if (await onSubmit(symbol, order)) setQuantity('')
  }
  return <section className="data-panel" aria-label="Submit an order">
    <div className="data-heading"><h2>Place an order</h2><span>{symbol} · Simulation</span></div>
    <form className="trading-form" onSubmit={(event) => { void submit(event) }}>
      <fieldset disabled={disabled}>
        <div className="trading-fields">
          <label>Side<select value={side} onChange={(event) => setSide(event.target.value as OrderSide)}><option value="BUY">BUY</option><option value="SELL">SELL</option></select></label>
          <label>Order type<select value={type} onChange={(event) => setType(event.target.value as OrderType)}><option value="LIMIT">LIMIT</option><option value="MARKET">MARKET</option></select></label>
          <label>Quantity<input required inputMode="numeric" value={quantity} onChange={(event) => setQuantity(event.target.value)} placeholder="Shares" /></label>
          <label>Limit price<input required={type === 'LIMIT'} disabled={type === 'MARKET'} inputMode="decimal" value={type === 'MARKET' ? '' : price} onChange={(event) => setPrice(event.target.value)} placeholder={type === 'MARKET' ? 'Best available' : 'Price per share'} /></label>
        </div>
        <p className="panel-footnote">{type === 'MARKET' ? 'Executes against available opposite-side orders. Any unfilled remainder is cancelled; the execution price is not guaranteed.' : 'Executes at your limit or better. Unfilled shares remain in the book.'}</p>
        {error && <p role="alert" className="trading-error">{error}</p>}
        <button type="submit" className="primary-button">Submit {side} order</button>
      </fieldset>
    </form>
  </section>
}
