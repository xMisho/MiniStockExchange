import { useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { orderQuantity, priceJson } from '../../api/orders'
import type { OrderSide, OrderType, SubmitOrder } from '../../types/orders'

interface Props { symbol: string; disabled: boolean; submitting?: boolean; onSubmit: (symbol: string, order: SubmitOrder) => Promise<boolean> }
export function OrderForm({ symbol, disabled, submitting = false, onSubmit }: Props) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [type, setType] = useState<OrderType>('LIMIT')
  const [quantity, setQuantity] = useState('')
  const [price, setPrice] = useState('')
  const [error, setError] = useState<{ field: 'quantity' | 'price'; message: string } | null>(null)
  const errorId = useId()
  const quantityInput = useRef<HTMLInputElement>(null)
  const priceInput = useRef<HTMLInputElement>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (disabled) return
    setError(null)
    let parsedQuantity: number
    let parsedPrice: string | undefined
    try { parsedQuantity = orderQuantity(quantity) }
    catch (failure) { setError({ field: 'quantity', message: failure instanceof Error ? failure.message : 'Check the quantity.' }); quantityInput.current?.focus(); return }
    if (type === 'LIMIT') {
      try { parsedPrice = priceJson(price) }
      catch (failure) { setError({ field: 'price', message: failure instanceof Error ? failure.message : 'Check the price.' }); priceInput.current?.focus(); return }
    }
    const order: SubmitOrder = { side, type, quantity: parsedQuantity, ...(parsedPrice ? { limitPrice: parsedPrice } : {}) }
    if (await onSubmit(symbol, order)) { setQuantity(''); quantityInput.current?.focus() }
  }
  return <section className="data-panel" aria-label="Submit an order" aria-busy={submitting}>
    <div className="data-heading"><div><p className="eyebrow">SIMULATED TRADING</p><h2>Place an order</h2></div><span>{symbol} · No real money</span></div>
    <form className={`trading-form side-${side.toLowerCase()}`} onSubmit={(event) => { void submit(event) }}>
      <fieldset disabled={disabled}>
        <legend className="sr-only">Order details for {symbol}</legend>
        <div className="trading-fields">
          <label>Side<select value={side} onChange={(event) => setSide(event.target.value as OrderSide)}><option value="BUY">BUY</option><option value="SELL">SELL</option></select></label>
          <label>Order type<select value={type} onChange={(event) => { setType(event.target.value as OrderType); setError(null) }}><option value="LIMIT">LIMIT</option><option value="MARKET">MARKET</option></select></label>
          <label>Quantity<input ref={quantityInput} required inputMode="numeric" aria-invalid={error?.field === 'quantity'} aria-describedby={error?.field === 'quantity' ? errorId : undefined} value={quantity} onChange={(event) => { setQuantity(event.target.value); if (error?.field === 'quantity') setError(null) }} placeholder="Shares" /></label>
          <label>Limit price<input ref={priceInput} required={type === 'LIMIT'} disabled={type === 'MARKET'} inputMode="decimal" aria-invalid={error?.field === 'price'} aria-describedby={error?.field === 'price' ? errorId : undefined} value={type === 'MARKET' ? '' : price} onChange={(event) => { setPrice(event.target.value); if (error?.field === 'price') setError(null) }} placeholder={type === 'MARKET' ? 'Best available' : 'Up to 4 decimals'} /></label>
        </div>
        <p className="panel-footnote">{type === 'MARKET' ? 'Executes against available opposite-side orders. Any unfilled remainder is cancelled; the execution price is not guaranteed.' : 'Executes at your limit or better. Unfilled shares remain in the book.'}</p>
        {error && <p role="alert" className="trading-error" id={errorId}>{error.message}</p>}
        <div className="form-actions"><p className="order-preview"><strong>{side} {symbol}</strong> · {type === 'LIMIT' ? 'Limit order' : 'Market order'}</p>
          <button type="submit" className="primary-button" aria-label={`Submit ${side} order`}>{submitting ? 'Submitting…' : `Submit ${side} order`}</button></div>
      </fieldset>
    </form>
  </section>
}
