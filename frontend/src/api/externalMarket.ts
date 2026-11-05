import { rateLimitedFetch, RateLimitError } from './rateLimit'
import { isFiniteNumber, isRecord } from './client'
import type { ExternalBar, ExternalHistory, ExternalQuote, ExternalResponse, HistoryRange, Instrument, MarketConnection } from '../types/externalMarket'
const instant = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const optionalInstant = (value: unknown) => value === null || instant(value)
const optionalNumber = (value: unknown) => value === null || isFiniteNumber(value)
const count = (value: unknown) => value === null || isFiniteNumber(value) && Number.isSafeInteger(value) && value >= 0
async function get(path: string, signal?: AbortSignal): Promise<unknown> {
  let response: Response
  try { response = await rateLimitedFetch(path, { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20000)]) : AbortSignal.timeout(20000) }) }
  catch (error) { if (error instanceof RateLimitError) throw error; throw new Error('Couldn’t reach the market-data server. Check your connection and retry.') }
  const body: unknown = await response.json()
  if (!response.ok) throw new Error(isRecord(body) && typeof body.error === 'string' ? body.error : 'External market data is unavailable.')
  return body
}
function envelope(value: unknown): value is ExternalResponse<unknown> {
  return isRecord(value) && value.provider === 'Alpaca' && value.feed === 'IEX' && value.currency === 'USD'
    && typeof value.coverage === 'string' && instant(value.fetchedAt) && typeof value.stale === 'boolean'
    && (value.warning === null || typeof value.warning === 'string') && isRecord(value.data)
}
export async function getMarketConnection(signal?: AbortSignal): Promise<MarketConnection> {
  const value = await get('/api/market/instruments', signal)
  if (!isRecord(value) || value.provider !== 'Alpaca' || value.feed !== 'IEX' || typeof value.configured !== 'boolean' || !Array.isArray(value.instruments)
    || value.instruments.length > 20 || !value.instruments.every((item): item is Instrument => isRecord(item) && typeof item.symbol === 'string' && /^[A-Z][A-Z0-9.-]{0,14}$/.test(item.symbol) && typeof item.companyName === 'string' && item.currency === 'USD')
    || new Set(value.instruments.map(item => item.symbol)).size !== value.instruments.length) throw new Error('Invalid external watchlist response.')
  return { provider: 'Alpaca', feed: 'IEX', configured: value.configured, instruments: value.instruments }
}
export async function getExternalQuote(key: string, signal?: AbortSignal): Promise<ExternalResponse<ExternalQuote>> {
  const symbol = key.replace(/^external:/, '')
  const value = await get(`/api/market/${encodeURIComponent(symbol)}/quote`, signal)
  if (!envelope(value)) throw new Error('Invalid external quote response.')
  const quote = value.data
  const numbers = ['price', 'bid', 'ask', 'previousClose', 'change', 'changePercent', 'sessionOpen', 'sessionHigh', 'sessionLow', 'sessionVolume', 'sessionVwap']
  if (!isRecord(quote) || quote.symbol !== symbol || !numbers.every(field => optionalNumber(quote[field])) || !count(quote.sessionTradeCount)
    || !optionalInstant(quote.priceAsOf) || !optionalInstant(quote.quoteAsOf) || !(quote.sessionDate === null || typeof quote.sessionDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(quote.sessionDate))) throw new Error('Invalid external quote response.')
  return value as ExternalResponse<ExternalQuote>
}
function bar(value: unknown): value is ExternalBar {
  return isRecord(value) && instant(value.startTime) && ['open', 'high', 'low', 'close'].every(field => isFiniteNumber(value[field]) && value[field] > 0)
    && isFiniteNumber(value.volume) && value.volume >= 0 && value.volume <= Number.MAX_SAFE_INTEGER && count(value.tradeCount)
    && (value.high as number) >= Math.max(value.open as number, value.close as number)
    && (value.low as number) <= Math.min(value.open as number, value.close as number)
}
export async function getExternalHistory(key: string, signal?: AbortSignal): Promise<ExternalResponse<ExternalHistory>> {
  const [, symbol, range] = key.split(':')
  const value = await get(`/api/market/${encodeURIComponent(symbol)}/candles?range=${encodeURIComponent(range)}`, signal)
  if (!envelope(value) || !isRecord(value.data)) throw new Error('Invalid external chart response.')
  const history = value.data
  if (history.symbol !== symbol || history.range !== range || !['1D', '1M', '1Y'].includes(range) || history.interval !== (range === '1D' ? '1Min' : range === '1M' ? '1Hour' : '1Day')
    || history.delayMinutes !== 15 || history.adjustment !== 'split' || !Array.isArray(history.candles) || history.candles.length > 1500 || !history.candles.every(bar)
    || !history.candles.every((item, index, all) => index === 0 || Date.parse(all[index - 1].startTime) < Date.parse(item.startTime))) throw new Error('Invalid external chart response.')
  return value as ExternalResponse<ExternalHistory>
}
export const historyKey = (symbol: string, range: HistoryRange) => `history:${symbol}:${range}`
