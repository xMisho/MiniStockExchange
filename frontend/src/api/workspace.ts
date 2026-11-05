import { rateLimitedFetch } from './rateLimit'
import { authenticatedRequest, csrfRequest } from './auth'
import { isFiniteNumber, isRecord } from './client'
import type { Instrument, ExternalResponse } from '../types/externalMarket'
export interface Alert { id: number; symbol: string; direction: 'ABOVE' | 'BELOW'; target: number; triggeredAt: string | null; triggeredPrice: number | null; lastObservedAt: string | null }
export interface AlertList { alerts: Alert[]; warning: string | null; newlyTriggeredIds?: number[] }
export interface Position { symbol: string; quantity: number; averagePrice: number; markPrice: number; marketValue: number; unrealizedPnl: number; realizedPnl: number }
export interface Portfolio { reservedCash?: number; availableCash?: number; startingCash: number; cash: number; equity: number; realizedPnl: number; unrealizedPnl: number; positions: Position[] }
export interface Session { status: 'REGULAR' | 'PRE_MARKET' | 'AFTER_HOURS' | 'CLOSED'; asOf: string; nextOpen: string; nextClose: string; regularOpen: string | null; regularClose: string | null; timezone: string }
const instant = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
export const isInstrument = (v: unknown): v is Instrument => isRecord(v) && typeof v.symbol === 'string' && /^[A-Z][A-Z0-9.-]{0,14}$/.test(v.symbol) && typeof v.companyName === 'string' && v.currency === 'USD'
async function publicRead(path: string, signal: AbortSignal): Promise<unknown> {
  const response = await rateLimitedFetch(path, { signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]), credentials: 'same-origin' })
  const value: unknown = await response.json()
  if (!response.ok) throw new Error(isRecord(value) && typeof value.error === 'string' ? value.error : 'Market lookup unavailable.')
  return value
}
export async function searchInstruments(query: string, signal: AbortSignal): Promise<Instrument[]> {
  const data = await publicRead(`/api/market/search?q=${encodeURIComponent(query)}`, signal)
  if (!Array.isArray(data) || data.length > 20 || !data.every(isInstrument)) throw new Error('Invalid search response.')
  return data
}
export async function getSession(_key: string, signal: AbortSignal): Promise<ExternalResponse<Session>> {
  const value = await publicRead('/api/market/session', signal)
  if (!isRecord(value) || value.provider !== 'Alpaca' || value.feed !== 'IEX' || typeof value.stale !== 'boolean' || !instant(value.fetchedAt) || !isRecord(value.data)) throw new Error('Invalid session response.')
  const d = value.data
  if (!['REGULAR', 'PRE_MARKET', 'AFTER_HOURS', 'CLOSED'].includes(d.status as string) || !instant(d.asOf) || !instant(d.nextOpen) || !instant(d.nextClose) || !(d.regularOpen === null || instant(d.regularOpen)) || !(d.regularClose === null || instant(d.regularClose))) throw new Error('Invalid session response.')
  return value as unknown as ExternalResponse<Session>
}
export async function getWatchlist(_key: string, signal: AbortSignal): Promise<Instrument[]> {
  const value: unknown = await (await authenticatedRequest('/api/watchlist', signal)).json()
  if (!Array.isArray(value) || value.length > 20 || !value.every(isInstrument)) throw new Error('Invalid watchlist response.')
  return value
}
export async function saveWatch(symbol: string) { await csrfRequest('/api/watchlist', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ symbol }) }) }
export async function removeWatch(symbol: string) { await csrfRequest(`/api/watchlist/${encodeURIComponent(symbol)}`, { method: 'DELETE' }) }
export async function getAlerts(_key: string, signal: AbortSignal): Promise<AlertList> {
  const v: unknown = await (await authenticatedRequest('/api/alerts', signal)).json()
  if (!isRecord(v) || !(v.newlyTriggeredIds === undefined || Array.isArray(v.newlyTriggeredIds) && v.newlyTriggeredIds.every(id => Number.isSafeInteger(id) && id > 0)) || !(v.warning === null || typeof v.warning === 'string') || !Array.isArray(v.alerts) || v.alerts.length > 20 || !v.alerts.every(a => isRecord(a) && Number.isSafeInteger(a.id) && typeof a.symbol === 'string' && ['ABOVE', 'BELOW'].includes(a.direction as string) && isFiniteNumber(a.target) && (a.triggeredAt === null || instant(a.triggeredAt)) && (a.triggeredPrice === null || isFiniteNumber(a.triggeredPrice)) && (a.lastObservedAt === null || instant(a.lastObservedAt)))) throw new Error('Invalid alert response.')
  return v as unknown as AlertList
}
export async function createAlert(symbol: string, direction: string, target: string) { await csrfRequest('/api/alerts', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ symbol, direction, target }) }) }
export async function removeAlert(id: number) { await csrfRequest(`/api/alerts/${id}`, { method: 'DELETE' }) }
export async function getPortfolio(_key: string, signal: AbortSignal): Promise<Portfolio> {
  const v: unknown = await (await authenticatedRequest('/api/portfolio', signal)).json()
  if (!isRecord(v) || !['startingCash', 'cash', 'equity', 'realizedPnl', 'unrealizedPnl'].every(k => isFiniteNumber(v[k])) || !Array.isArray(v.positions) || !v.positions.every(p => isRecord(p) && typeof p.symbol === 'string' && Number.isSafeInteger(p.quantity) && ['averagePrice', 'markPrice', 'marketValue', 'unrealizedPnl', 'realizedPnl'].every(k => isFiniteNumber(p[k])))) throw new Error('Invalid portfolio response.')
  return v as unknown as Portfolio
}
export async function registerStock(symbol: string, companyName: string, referencePrice: string) {
  await csrfRequest('/api/stocks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ symbol, companyName, referencePrice }) })
}
