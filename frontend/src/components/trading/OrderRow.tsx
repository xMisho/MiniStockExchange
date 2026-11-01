import type { Order } from '../../types/orders'
import { formatCount, formatOrderPrice, formatUtcDateTime } from '../../utils/format'
import { OrderActions } from './OrderActions'

interface Props { order: Order; disabled: boolean; pending: boolean; feedback?: { message: string; uncertain: boolean }; onAmend: (order: Order, price: string, remaining: string) => Promise<boolean>; onCancel: (order: Order) => Promise<boolean> }
export function OrderRow({ order, ...actions }: Props) {
  return <tr role="row" className="order-row">
    <td role="cell" data-label="Order" className="order-identity"><strong>#{order.id}</strong><span>{order.symbol}</span><time dateTime={order.createdAt}>{formatUtcDateTime(order.createdAt)}</time></td>
    <td role="cell" data-label="Side / type"><span className={order.side === 'BUY' ? 'positive' : 'negative'}>{order.side}</span><span className="cell-secondary">{order.type}</span></td>
    <td role="cell" data-label="Limit price">{formatOrderPrice(order.limitPrice)}</td>
    <td role="cell" data-label="Original qty">{formatCount(order.originalQuantity)}</td>
    <td role="cell" data-label="Filled">{formatCount(order.filledQuantity)}</td>
    <td role="cell" data-label="Remaining">{formatCount(order.remainingQuantity)}</td>
    <td role="cell" data-label="Status" className="order-state-cell"><span className={`order-status status-${order.status.toLowerCase()}`}>{order.status.replace('_', ' ')}</span></td>
    <td role="cell" data-label="Actions" className="order-actions-cell"><OrderActions order={order} {...actions} /></td>
  </tr>
}
