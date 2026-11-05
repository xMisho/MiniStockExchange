import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { getOrderPreview, getTradingBalance, orderQuantity, priceJson } from '../../api/orders'
import { getMarketSnapshot } from '../../api/marketData'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import { bookPriceEvent } from '../../utils/bookEvents'
import { formatCount, formatOrderPrice, formatPrice, formatUtcTime } from '../../utils/format'
import type { StockMetadata } from '../../types/market'
import type { FillPreview } from '../../api/orders'
import type { OrderSide, OrderType, SubmitOrder } from '../../types/orders'

interface Props { symbol: string; disabled: boolean; submitting?: boolean; active?: boolean; stocks?: StockMetadata[]; onSymbolChange?: (symbol: string) => void; onSubmit: (symbol: string, order: SubmitOrder) => Promise<boolean> }
export function OrderForm({ symbol, disabled, submitting = false, active = true, stocks, onSymbolChange, onSubmit }: Props) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [type, setType] = useState<OrderType>('LIMIT')
  const [quantity, setQuantity] = useState('')
  const [price, setPrice] = useState('')
  const [error, setError] = useState<{ field: 'quantity' | 'price'; message: string } | null>(null)
  const [estimate, setEstimate] = useState<{ key: string; value?: FillPreview; error?: string } | null>(null)
  const quote = useSymbolResource(active ? symbol : null, getMarketSnapshot)
  const account = useSymbolResource(active ? symbol : null, getTradingBalance, 5000)
  const snapshot = quote.resource.status === 'ready' ? quote.resource.data : null
  const portfolio = account.resource.status === 'ready' ? account.resource.data : null
  const estimateKey = `${symbol}:${side}:${quantity}`
  const preview = estimate?.key === estimateKey ? estimate.value : null
  useEffect(() => {
    const choose = (event: Event) => {
      const detail = (event as CustomEvent<{ symbol: string; price: number }>).detail
      if (detail.symbol !== symbol || disabled) return
      setType('LIMIT'); setPrice(String(detail.price)); setError(null)
      priceInput.current?.focus()
    }
    window.addEventListener(bookPriceEvent, choose)
    return () => window.removeEventListener(bookPriceEvent, choose)
  }, [symbol, disabled])
  useEffect(() => {
    if (type !== 'MARKET' || disabled || !active) return
    let count: number
    try { count = orderQuantity(quantity) } catch { return }
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const run = async () => {
      if (document.visibilityState === 'hidden') { timer = setTimeout(() => { void run() }, 5000); return }
      try {
        const value = await getOrderPreview(symbol, side, count, controller.signal)
        if (!controller.signal.aborted) setEstimate({ key: estimateKey, value })
      } catch {
        if (!controller.signal.aborted) setEstimate({ key: estimateKey, error: 'Fill estimate unavailable. Execution prices and liquidity are unknown.' })
      }
      if (!controller.signal.aborted) timer = setTimeout(() => { void run() }, 5000)
    }
    timer = setTimeout(() => { void run() }, 300)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [symbol, side, quantity, type, disabled, active, estimateKey])
  let limitValue: number | null = null
  try { limitValue = orderQuantity(quantity) * Number(priceJson(price)) } catch { limitValue = null }
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
    <div className="ticket-context"><strong>Local simulated exchange · {symbol}</strong><p>Local bid {formatOrderPrice(snapshot?.bestBidPrice ?? null)} · Ask {formatOrderPrice(snapshot?.bestAskPrice ?? null)}</p>
      <p>Available paper cash {portfolio ? `$${formatPrice(portfolio.availableCash)}` : '—'} · Available holdings {portfolio ? formatCount(portfolio.availableShares) : '—'} shares</p>
      <small>Open limit orders reserve cash or shares. Short selling and borrowing are disabled. {account.refreshError || quote.refreshError ? 'Previous data: refresh unavailable.' : ''}</small></div>
    <form className={`trading-form side-${side.toLowerCase()}`} onSubmit={(event) => { void submit(event) }}>
      <fieldset disabled={disabled}>
        <legend className="sr-only">Order details for {symbol}</legend>
        {stocks && onSymbolChange && <div className="ticket-stock-picker"><label>Trade stock<select value={symbol} onChange={event => onSymbolChange(event.target.value)}>{stocks.map(stock => <option key={stock.symbol} value={stock.symbol}>{stock.symbol} · {stock.companyName}</option>)}</select></label><p className="panel-footnote">Choose a registered local stock. This also updates the chart and order book. Additional symbols must be registered by an admin.</p></div>}
        <div className="trading-fields">
          <label>Side<select value={side} onChange={(event) => setSide(event.target.value as OrderSide)}><option value="BUY">BUY</option><option value="SELL">SELL</option></select></label>
          <label>Order type<select value={type} onChange={(event) => { setType(event.target.value as OrderType); setError(null) }}><option value="LIMIT">LIMIT</option><option value="MARKET">MARKET</option></select></label>
          <label>Quantity<input ref={quantityInput} required inputMode="numeric" aria-invalid={error?.field === 'quantity'} aria-describedby={error?.field === 'quantity' ? errorId : undefined} value={quantity} onChange={(event) => { setQuantity(event.target.value); if (error?.field === 'quantity') setError(null) }} placeholder="Shares" /></label>
          <label>Limit price<input ref={priceInput} required={type === 'LIMIT'} disabled={type === 'MARKET'} inputMode="decimal" aria-invalid={error?.field === 'price'} aria-describedby={error?.field === 'price' ? errorId : undefined} value={type === 'MARKET' ? '' : price} onChange={(event) => { setPrice(event.target.value); if (error?.field === 'price') setError(null) }} placeholder={type === 'MARKET' ? 'Best available' : 'Up to 4 decimals'} /></label>
        </div>
        <p className="panel-footnote">{type === 'MARKET' ? 'Executes against available opposite-side orders. Any unfilled remainder is cancelled; the execution price is not guaranteed.' : 'Executes at your limit or better. Unfilled shares remain in the book.'}</p>
        <div className="order-estimate" aria-live="polite">
          {type === 'LIMIT' ? <p>Estimated limit value: <strong>{limitValue !== null ? `$${formatPrice(limitValue)}` : '—'}</strong> · Quantity × limit; actual fills may be better.</p>
            : preview ? <><p>Estimated fill: <strong>{preview.filledQuantity} / {preview.requestedQuantity} shares</strong> · ${formatPrice(preview.estimatedValue)}</p><p>Average {formatOrderPrice(preview.averagePrice)} · Worst {formatOrderPrice(preview.worstPrice)}</p>
              {preview.unfilledQuantity > 0 && <p className="source-warning">Insufficient local liquidity: {preview.unfilledQuantity} shares would be cancelled.</p>}
              <small>Full local book as of {formatUtcTime(preview.observedAt)}. Estimate only; liquidity can change before submission.</small></>
              : <p>{estimate?.key === estimateKey && estimate.error ? estimate.error : 'Enter a valid quantity to preview current local fills.'}</p>}
        </div>
        {error && <p role="alert" className="trading-error" id={errorId}>{error.message}</p>}
        <div className="form-actions"><p className="order-preview"><strong>{side} {symbol}</strong> · {type === 'LIMIT' ? 'Limit order' : 'Market order'}</p>
          <button type="submit" className="primary-button" aria-label={`Submit ${side} order`}>{submitting ? 'Submitting…' : `Submit ${side} order`}</button></div>
      </fieldset>
    </form>
  </section>
}
