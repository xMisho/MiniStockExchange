export type OrderStatus = 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED'
export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'LIMIT' | 'MARKET'
export interface Order {
  id: number
  userId: number
  symbol: string
  side: OrderSide
  type: OrderType
  limitPrice: number | null
  originalQuantity: number
  remainingQuantity: number
  filledQuantity: number
  status: OrderStatus
  createdAt: string
  updatedAt: string
}
export interface OrderPage { content: Order[]; page: number; size: number; totalElements: number; totalPages: number }
export interface SubmitOrder { side: OrderSide; type: OrderType; quantity: number; limitPrice?: string }
export const orderStatuses: OrderStatus[] = ['OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED']
export function isOpenLimit(order: Order) { return order.type === 'LIMIT' && (order.status === 'OPEN' || order.status === 'PARTIALLY_FILLED') }
