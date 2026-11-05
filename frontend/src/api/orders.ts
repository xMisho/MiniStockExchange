import { authenticatedRequest, AuthError, csrfRequest } from './auth'
import { isFiniteNumber, isRecord } from './client'
import { orderStatuses } from '../types/orders'
import type { Order, OrderPage, OrderStatus, SubmitOrder } from '../types/orders'

export class OrderRequestError extends Error {
  uncertain: boolean
  status: number
  constructor(message: string, uncertain = false, status = 0) { super(message); this.uncertain = uncertain; this.status = status }
}
const counter = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const instant = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
function isOrder(value: unknown): value is Order {
  return isRecord(value) && counter(value.id) && value.id > 0 && counter(value.userId) && value.userId > 0
    && typeof value.symbol === 'string' && value.symbol.length > 0 && ['BUY', 'SELL'].includes(value.side as string)
    && ['LIMIT', 'MARKET'].includes(value.type as string) && orderStatuses.includes(value.status as OrderStatus)
    && (value.type === 'MARKET' ? value.limitPrice === null : isFiniteNumber(value.limitPrice) && value.limitPrice > 0)
    && counter(value.originalQuantity) && value.originalQuantity > 0 && counter(value.remainingQuantity) && counter(value.filledQuantity)
    && instant(value.createdAt) && instant(value.updatedAt)
}
export function priceJson(value: string): string {
  const match = /^(\d+)(?:\.(\d{1,4}))?$/.exec(value.trim())
  if (!match) throw new Error('Price must be positive with at most 4 decimal places.')
  const whole = match[1].replace(/^0+/, '') || '0'
  const price = whole + (match[2] ? `.${match[2]}` : '')
  if (whole.length > 15 || !/[1-9]/.test(price)) throw new Error('Price must be positive and fit 15 integer digits and 4 decimal places.')
  return price
}
export function orderQuantity(value: string): number {
  if (!/^\d+$/.test(value)) throw new Error('Quantity must be a positive whole number.')
  const quantity = Number(value)
  if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > 2147483647) throw new Error('Quantity must be between 1 and 2,147,483,647.')
  return quantity
}
async function mutate(path: string, method: string, body?: string, requestKey?: string) {
  try { return await csrfRequest(path, { method, headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(requestKey ? { 'Idempotency-Key': requestKey } : {}) }, ...(body === undefined ? {} : { body }) }) }
  catch (error) {
    if (error instanceof AuthError) {
      const uncertain = error.mutationSent && (error.status === 0 || error.status >= 500 || error.retrySafe === false || error.code === 'TRADING_OUTCOME_UNCERTAIN')
      throw new OrderRequestError(error.message, uncertain, error.status)
    }
    throw new OrderRequestError(error instanceof Error ? error.message : 'Couldn’t prepare the order request.')
  }
}
export async function submitOrder(symbol: string, order: SubmitOrder, requestKey: string = crypto.randomUUID()): Promise<Order> {
  const quantity = orderQuantity(String(order.quantity))
  const fields = JSON.stringify({ side: order.side, type: order.type, quantity })
  const body = order.type === 'LIMIT' ? `${fields.slice(0, -1)},"limitPrice":${priceJson(order.limitPrice ?? '')}}` : fields
  const response = await mutate(`/api/stocks/${encodeURIComponent(symbol)}/orders`, 'POST', body, requestKey)
  try {
    const result: unknown = await response.json()
    if (!isOrder(result) || result.symbol !== symbol) throw new Error('Invalid order response')
    return result
  } catch { throw new OrderRequestError('The server accepted the request, but its result could not be read.', true) }
}
export async function getMyOrders(page: number, status: OrderStatus | '', signal?: AbortSignal): Promise<OrderPage> {
  const query = new URLSearchParams({ page: String(page), size: '25' })
  if (status) query.set('status', status)
  const response = await authenticatedRequest(`/api/orders/me?${query}`, signal)
  const result: unknown = await response.json()
  if (!isRecord(result) || !Array.isArray(result.content) || !result.content.every(isOrder) || result.content.length > 25
    || result.page !== page || result.size !== 25 || !counter(result.totalElements) || !counter(result.totalPages)
    || result.totalElements < result.content.length || result.totalPages !== Math.ceil(result.totalElements / 25)
    || !result.content.every((order, index, orders) => (index === 0 || orders[index - 1].id > order.id) && (!status || order.status === status))) {
    throw new Error('The order history response is invalid. Please refresh My Orders.')
  }
  return { content: result.content, page, size: 25, totalElements: result.totalElements, totalPages: result.totalPages }
}
export async function amendOrder(id: number, newPrice: string, newRemainingQuantity: string) {
  const body = `{"newPrice":${priceJson(newPrice)},"newRemainingQuantity":${orderQuantity(newRemainingQuantity)}}`
  await mutate(`/api/orders/${id}`, 'PATCH', body)
}
export async function cancelOrder(id: number) { await mutate(`/api/orders/${id}`, 'DELETE') }

export interface FillPreview { requestedQuantity: number; filledQuantity: number; unfilledQuantity: number; estimatedValue: number; averagePrice: number | null; worstPrice: number | null; observedAt: string }
export async function getOrderPreview(symbol: string, side: string, quantity: number, signal: AbortSignal): Promise<FillPreview> {
  const response = await authenticatedRequest(`/api/stocks/${encodeURIComponent(symbol)}/order-preview?side=${side}&quantity=${quantity}`, signal)
  const v: unknown = await response.json()
  if (!isRecord(v) || v.requestedQuantity !== quantity || !counter(v.filledQuantity) || !counter(v.unfilledQuantity)
    || v.filledQuantity + v.unfilledQuantity !== quantity || !isFiniteNumber(v.estimatedValue)
    || v.estimatedValue < 0 || !(v.averagePrice === null || isFiniteNumber(v.averagePrice)) || !(v.worstPrice === null || isFiniteNumber(v.worstPrice)) || !instant(v.observedAt)) throw new Error('Invalid fill estimate.')
  return v as unknown as FillPreview
}
export interface OrderEvent { id: number; kind: string; occurred_at: string; price: number | null; quantity: number; previous_price: number | null; previous_remaining: number | null; remaining_quantity: number | null; filled_quantity: number | null }
export async function getOrderEvents(id: number, signal: AbortSignal): Promise<OrderEvent[]> {
  const response = await authenticatedRequest(`/api/orders/${id}/lifecycle`, signal)
  const v: unknown = await response.json()
  if (!Array.isArray(v) || v.length > 100 || !v.every(e => isRecord(e) && counter(e.id) && ['SUBMITTED', 'AMENDED', 'CANCELLED', 'FILL'].includes(e.kind as string) && instant(e.occurred_at) && counter(e.quantity) && (e.price === null || isFiniteNumber(e.price)) && (e.previous_price === null || isFiniteNumber(e.previous_price)) && ['previous_remaining', 'remaining_quantity', 'filled_quantity'].every(k => e[k] === null || counter(e[k])))) throw new Error('Invalid order activity response.')
  return v as OrderEvent[]
}

export interface TradingBalance { cash: number; reservedCash: number; availableCash: number; holdings: number; reservedShares: number; availableShares: number }
export async function getTradingBalance(symbol: string, signal: AbortSignal): Promise<TradingBalance> {
  const value: unknown = await (await authenticatedRequest(`/api/stocks/${encodeURIComponent(symbol)}/balance`, signal)).json()
  if (!isRecord(value) || !['cash', 'reservedCash', 'availableCash', 'holdings', 'reservedShares', 'availableShares'].every(k => isFiniteNumber(value[k]))) throw new Error('Invalid account balance response.')
  return value as unknown as TradingBalance
}
export async function allocatePaperShares(userId: number, symbol: string, quantity: number) {
  await csrfRequest('/api/admin/paper-allocations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId, symbol, quantity }) })
}
