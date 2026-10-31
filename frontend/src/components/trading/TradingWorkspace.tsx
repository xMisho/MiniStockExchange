import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../../auth/authState'
import { amendOrder, cancelOrder, OrderRequestError, submitOrder } from '../../api/orders'
import type { Order, OrderStatus, SubmitOrder } from '../../types/orders'
import { refreshMarket } from '../../utils/marketEvents'
import { OrderForm } from './OrderForm'
import { MyOrders } from './MyOrders'

type Notice = { kind: 'success' | 'error' | 'uncertain'; message: string }
export function TradingWorkspace({ symbol }: { symbol: string | null }) {
  const auth = useAuth()
  if (auth.status === 'loading') return <section className="data-panel" id="trading"><p className="panel-message">Checking your session before enabling trading…</p></section>
  if (!auth.user) return <section className="data-panel" id="trading"><h2>Trade in your exchange</h2><p className="panel-message">Sign in using the header to place orders and manage My Orders. Public market data remains available.</p></section>
  return <AuthenticatedTrading key={auth.user.id} symbol={symbol} authBusy={auth.busy} holdSession={auth.holdSession} />
}
function AuthenticatedTrading({ symbol, authBusy, holdSession }: { symbol: string | null; authBusy: boolean; holdSession: () => () => void }) {
  const [busy, setBusy] = useState(false)
  const [blocked, setBlocked] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<OrderStatus | ''>('')
  const [revision, setRevision] = useState(0)
  const inFlight = useRef(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const refreshOrders = () => setRevision((value) => value + 1)
  const perform = async (targetSymbol: string, action: () => Promise<string>, submission = false) => {
    if (inFlight.current || blocked || authBusy) return false
    inFlight.current = true; setBusy(true); setNotice(null)
    const release = holdSession()
    try {
      const message = await action()
      refreshMarket(targetSymbol)
      if (mounted.current) {
        setNotice({ kind: 'success', message }); refreshOrders()
        if (submission) { setPage(0); setStatus('') }
      }
      return true
    } catch (error) {
      const uncertain = error instanceof OrderRequestError && error.uncertain
      if (uncertain || (error instanceof OrderRequestError && error.status === 409)) refreshMarket(targetSymbol)
      if (mounted.current) {
        setNotice({ kind: uncertain ? 'uncertain' : 'error', message: error instanceof Error ? error.message : 'The order request failed.' })
        if (uncertain) { setBlocked(true); setPage(0); setStatus(''); refreshOrders() }
        else if (error instanceof OrderRequestError && error.status === 409) refreshOrders()
      }
      return false
    } finally { release(); inFlight.current = false; if (mounted.current) setBusy(false) }
  }
  const submit = (targetSymbol: string, order: SubmitOrder) => perform(targetSymbol, async () => {
    const result = await submitOrder(targetSymbol, order)
    return `Order #${result.id} · ${result.symbol} · ${result.status.replace('_', ' ')}. Filled ${result.filledQuantity}; remaining ${result.remainingQuantity}.`
  }, true)
  const amend = (order: Order, price: string, remaining: string) => perform(order.symbol, async () => {
    await amendOrder(order.id, price, remaining)
    return `Amendment confirmed for order #${order.id} · ${order.symbol}. My Orders is refreshing with the resulting state.`
  })
  const cancel = (order: Order) => perform(order.symbol, async () => {
    await cancelOrder(order.id)
    return `Cancellation confirmed for order #${order.id} · ${order.symbol}.`
  })
  const disabled = busy || blocked || authBusy
  return <div className="trading-workspace" id="trading">
    {notice && <div className={`trading-notice ${notice.kind}`} role={notice.kind === 'success' ? 'status' : 'alert'}>
      <p>{notice.message}</p>
      {notice.kind === 'uncertain' && <>
        <p><strong>Do not resubmit blindly.</strong> The request may already have committed. Check My Orders, including filled and cancelled orders, before deciding whether to try again. Mutations are paused until you confirm that review.</p>
        <button type="button" className="text-button" onClick={() => { setPage(0); setStatus(''); refreshOrders(); document.getElementById('my-orders-title')?.focus() }}>Check My Orders</button>
        <button type="button" className="text-button" onClick={() => { setBlocked(false); setNotice(null) }}>I have checked My Orders</button>
      </>}
    </div>}
    {busy && <p role="status" className="panel-footnote">Waiting for the server to confirm your order action…</p>}
    {symbol ? <OrderForm key={symbol} symbol={symbol} disabled={disabled} onSubmit={submit} /> : <p className="panel-message">Select a registered stock to place an order.</p>}
    <MyOrders page={page} status={status} revision={revision} disabled={disabled} onPage={setPage} onStatus={(next) => { setStatus(next); setPage(0) }} onRefresh={refreshOrders} onAmend={amend} onCancel={cancel} />
  </div>
}
