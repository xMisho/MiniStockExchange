import { useEffect, useState } from 'react'
import { getMyOrders } from '../../api/orders'
import { orderStatuses } from '../../types/orders'
import type { Order, OrderPage, OrderStatus } from '../../types/orders'
import { formatCount, formatOrderPrice, formatUtcDateTime } from '../../utils/format'
import { OrderActions } from './OrderActions'

interface Props {
  page: number; status: OrderStatus | ''; revision: number; disabled: boolean
  onPage: (page: number) => void; onStatus: (status: OrderStatus | '') => void; onRefresh: () => void
  onAmend: (order: Order, price: string, remaining: string) => Promise<boolean>; onCancel: (order: Order) => Promise<boolean>
}
type State = { key: string } & ({ status: 'ready'; data: OrderPage } | { status: 'error'; message: string })
export function MyOrders({ page, status, revision, disabled, onPage, onStatus, onRefresh, onAmend, onCancel }: Props) {
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
  return <section className="data-panel my-orders" aria-labelledby="my-orders-title" id="my-orders">
    <div className="data-heading"><h2 id="my-orders-title" tabIndex={-1}>My Orders</h2><button type="button" className="text-button" onClick={onRefresh}>Refresh My Orders</button></div>
    <div className="order-history-tools"><label>Order status<select value={status} onChange={(event) => onStatus(event.target.value as OrderStatus | '')}>
      <option value="">All statuses</option>{orderStatuses.map((value) => <option value={value} key={value}>{value.replace('_', ' ')}</option>)}
    </select></label><span>Your orders only · Newest ID first · Times in UTC</span></div>
    {!current ? <p className="panel-message" role="status">Loading your orders…</p> : current.status === 'error' ? <div role="alert" className="panel-message"><p>{current.message}</p><button className="primary-button" type="button" onClick={onRefresh}>Retry My Orders</button></div>
      : data && <>
        {data.content.length === 0 ? <p className="panel-message">No orders on this page{status ? ` with status ${status.replace('_', ' ')}` : ''}.</p> : <div className="table-scroll">
          <table className="market-table order-table"><caption className="sr-only">Your paginated orders</caption>
            <thead><tr>{['ID', 'Symbol', 'Side', 'Type', 'Limit price', 'Original qty', 'Filled', 'Remaining', 'Status', 'Created (UTC)', 'Actions'].map((label) => <th scope="col" key={label}>{label}</th>)}</tr></thead>
            <tbody>{data.content.map((order) => <tr key={order.id}>
              <td>#{order.id}</td><td>{order.symbol}</td><td>{order.side}</td><td>{order.type}</td><td>{formatOrderPrice(order.limitPrice)}</td><td>{formatCount(order.originalQuantity)}</td>
              <td>{formatCount(order.filledQuantity)}</td><td>{formatCount(order.remainingQuantity)}</td><td><span className={`order-status status-${order.status.toLowerCase()}`}>{order.status.replace('_', ' ')}</span></td>
              <td><time dateTime={order.createdAt}>{formatUtcDateTime(order.createdAt)}</time></td>
              <td><OrderActions order={order} disabled={disabled} onAmend={onAmend} onCancel={onCancel} /></td>
            </tr>)}</tbody>
          </table>
        </div>}
        <div className="order-pagination"><button type="button" className="text-button" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous orders</button>
          <span>Page {page + 1}{data.totalPages > 0 ? ` of ${data.totalPages}` : ''} · {formatCount(data.totalElements)} orders</span>
          <button type="button" className="text-button" disabled={page + 1 >= data.totalPages || (page + 1) * 25 > 2147483647} onClick={() => onPage(page + 1)}>Next orders</button></div>
      </>}
  </section>
}
