import { authenticatedRequest, csrfRequest } from '../api/auth'
import { isRecord, isFiniteNumber } from '../api/client'
export interface PaperOrder { id: number; symbol: string; side: 'BUY' | 'SELL'; type: 'LIMIT' | 'MARKET'; originalQuantity: number; quantity: number; limitPrice: number | null; status: 'OPEN' | 'FILLED' | 'CANCELLED'; createdAt: string; updatedAt: string; executionPrice: number | null; quoteAt: string | null; executedAt: string | null }
export interface PaperPage { content: PaperOrder[]; page: number; size: number; totalElements: number; totalPages: number }
export interface PaperPosition { symbol: string; quantity: number; reservedShares: number; availableShares: number; averagePrice: number; markPrice: number | null; markAsOf: string | null; marketValue: number | null; unrealizedPnl: number | null; realizedPnl: number }
export interface PaperPortfolio { cash: number; reservedCash: number; availableCash: number; equity: number | null; realizedPnl: number; unrealizedPnl: number | null; positions: PaperPosition[] }
export interface PaperRequest { symbol: string; side: 'BUY' | 'SELL'; type: 'LIMIT' | 'MARKET'; quantity: number; limitPrice?: string; key: string }
function order(v: unknown): v is PaperOrder { return isRecord(v) && Number.isSafeInteger(v.id) && typeof v.symbol === 'string' && ['BUY','SELL'].includes(String(v.side)) && ['MARKET','LIMIT'].includes(String(v.type)) && ['OPEN','FILLED','CANCELLED'].includes(String(v.status)) && Number.isSafeInteger(v.quantity) && typeof v.createdAt === 'string' && (v.executionPrice === null || isFiniteNumber(v.executionPrice)) }
export async function orders(page: number, signal?: AbortSignal): Promise<PaperPage> {
 const v: unknown = await (await authenticatedRequest(`/api/paper/orders?page=${page}`, signal)).json()
 if (!isRecord(v) || !Array.isArray(v.content) || !v.content.every(order) || v.page !== page || v.size !== 25 || !Number.isSafeInteger(v.totalElements) || !Number.isSafeInteger(v.totalPages)) throw new Error('Invalid paper order history.')
 return v as unknown as PaperPage
}
export async function portfolio(_key: string, signal: AbortSignal): Promise<PaperPortfolio> {
 const v: unknown = await (await authenticatedRequest('/api/paper/portfolio', signal)).json()
 if (!isRecord(v) || !['cash','reservedCash','availableCash','realizedPnl'].every(k => isFiniteNumber(v[k])) || !Array.isArray(v.positions) || !v.positions.every(p => isRecord(p) && typeof p.symbol === 'string' && Number.isSafeInteger(p.quantity) && Number.isSafeInteger(p.availableShares))) throw new Error('Invalid paper portfolio.')
 return v as unknown as PaperPortfolio
}
export async function fills(symbol: string, signal: AbortSignal): Promise<PaperOrder[]> {
 const v: unknown = await (await authenticatedRequest(`/api/paper/fills?symbol=${encodeURIComponent(symbol)}`, signal)).json()
 if (!Array.isArray(v) || !v.every(order)) throw new Error('Invalid paper fills.')
 return v
}
export async function submit(r: PaperRequest): Promise<PaperOrder> {
 const response = await csrfRequest('/api/paper/orders', { method:'POST', headers:{ 'Content-Type':'application/json', 'Idempotency-Key':r.key }, body:JSON.stringify({symbol:r.symbol,side:r.side,type:r.type,quantity:r.quantity,...(r.type === 'LIMIT' ? {limitPrice:r.limitPrice} : {})}) })
 const v: unknown = await response.json(); if (!order(v)) throw new Error('Could not read the submission result. Review My Orders before retrying with the same request key.')
 return v
}
export async function cancel(id: number) { await csrfRequest(`/api/paper/orders/${id}`, {method:'DELETE'}) }
export async function amend(id: number, limitPrice: string, quantity: number) { await csrfRequest(`/api/paper/orders/${id}`, {method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({limitPrice,quantity})}) }
