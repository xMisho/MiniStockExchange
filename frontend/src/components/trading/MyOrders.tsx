import { useEffect, useState } from 'react'
import { getMyOrders } from '../../api/orders'
import { orderStatuses } from '../../types/orders'
import type { Order, OrderPage, OrderStatus } from '../../types/orders'
import { formatCount } from '../../utils/format'
import { OrderRow } from './OrderRow'

interface Props {
  page: number; status: OrderStatus | ''; revision: number; disabled: boolean; pending: boolean; feedback?: { message: string; uncertain: boolean }
  onPage: (page: number) => void; onStatus: (status: OrderStatus | '') => void; onRefresh: () => void
  onAmend: (order: Order, price: string, remaining: string) => Promise<boolean>; onCancel: (order: Order) => Promise<boolean>
}
type State = { key: string } & ({ status: 'ready'; data: OrderPage } | { status: 'error'; message: string })
export function MyOrders({ page, status, revision, disabled, pending, feedback, onPage, onStatus, onRefresh, onAmend, onCancel }: Props) {
  const [state, setState] = useState<State | null>(null)
  const key = `${page}:${status}:${revision}`
  useEffect(() => {
    const controller = new AbortController()
    getMyOrders(page, status, controller.signal).then((data) => {
      if (!controller.signal.aborted) setState({ key, status: 'ready', data })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setState({ key, status: 'error', message: error instanceof Error ? error.message : 'Couldn’t load your orders.' })
    })
    return () => controller.abort()
  }, [page, status, key])
  const current = state?.key === key ? state : null
  const data = current?.status === 'ready' ? current.data : null
  return <section className="data-panel my-orders" aria-busy={!current} aria-labelledby="my-orders-title" id="my-orders">
    <div className="data-heading"><div><p className="eyebrow">YOUR ACTIVITY</p><h2 id="my-orders-title" tabIndex={-1}>My Orders</h2></div><button type="button" className="text-button" onClick={onRefresh}>Refresh My Orders</button></div>
    <p className="panel-footnote">OPEN means waiting for a match. Amend changes only the outstanding part of an order; filled shares cannot be amended. To sell shares you own, use Portfolio → Sell shares to create a new SELL order.</p>
    <div className="order-history-tools"><label>Order status<select value={status} onChange={(event) => onStatus(event.target.value as OrderStatus | '')}>
      <option value="">All statuses</option>{orderStatuses.map((value) => <option value={value} key={value}>{value.replace('_', ' ')}</option>)}
    </select></label><span>Your orders only · Newest ID first · Times in UTC</span></div>
    {!current ? <p className="panel-message" role="status">Loading your orders…</p> : current.status === 'error' ? <div role="alert" className="panel-message"><p>{current.message}</p><button className="primary-button" type="button" onClick={onRefresh}>Retry My Orders</button></div>
      : data && <>
        {data.content.length === 0 ? <div className="empty-orders"><h3>No orders to show</h3><p>No orders on this page{status ? ` with status ${status.replace('_', ' ')}` : ''}. {status ? 'Try another status or view all orders.' : 'Your submitted orders will appear here.'}</p></div> : <div className="order-table-container">
          <table className="market-table order-table" role="table"><caption className="sr-only">Your paginated orders. Original quantity, filled shares and remaining shares are shown separately. Times are UTC.</caption>
            <thead role="rowgroup"><tr role="row">{['Order / created (UTC)', 'Side / type', 'Limit price', 'Original qty', 'Filled', 'Remaining', 'Status', 'Actions'].map((label) => <th role="columnheader" scope="col" key={label}>{label}</th>)}</tr></thead>
            <tbody role="rowgroup">{data.content.map((order) => <OrderRow key={order.id} order={order} disabled={disabled} pending={pending} feedback={feedback} onAmend={onAmend} onCancel={onCancel} />)}</tbody>
          </table>
        </div>}
        <div className="order-pagination"><button type="button" className="text-button" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous orders</button>
          <span>Page {page + 1}{data.totalPages > 0 ? ` of ${data.totalPages}` : ''} · {formatCount(data.totalElements)} orders</span>
          <button type="button" className="text-button" disabled={page + 1 >= data.totalPages || (page + 1) * 25 > 2147483647} onClick={() => onPage(page + 1)}>Next orders</button></div>
      </>}
  </section>
}
