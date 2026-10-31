import { getJson, isFiniteNumber, isRecord } from './client'
import type { MarketSnapshot, MarketDepth, MarketTrade, TradePage, Candle, PriceLevel } from '../types/market'

export async function getMarketSnapshot(symbol: string, signal?: AbortSignal): Promise<MarketSnapshot> {
  const data = await getJson(`/api/stocks/${encodeURIComponent(symbol)}/snapshot`, signal)
  if (!isSnapshot(data) || data.symbol !== symbol) {
    throw new Error('The market summary response is invalid.')
  }
  return data
}

function isSnapshot(value: unknown): value is MarketSnapshot {
  if (!isRecord(value) || typeof value.symbol !== 'string') return false
  const decimalFields = ['lastTradedPrice', 'priceChange', 'priceChangePercent', 'bestBidPrice', 'bestAskPrice', 'spread', 'vwap']
  const counters = ['volume', 'tradeCount', 'openOrderCount']
  return decimalFields.every((field) => value[field] === null || isFiniteNumber(value[field]))
    && counters.every((field) => isFiniteNumber(value[field]) && Number.isSafeInteger(value[field]) && value[field] >= 0)
}

function isCounter(value: unknown): value is number {
  return isFiniteNumber(value) && Number.isSafeInteger(value) && value >= 0
}

function isPositive(value: unknown): value is number {
  return isFiniteNumber(value) && value > 0
}

function isInstant(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false
  const milliseconds = Date.parse(value)
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString().slice(0, 19) === value.slice(0, 19)
}

function isLevel(value: unknown): value is PriceLevel {
  return isRecord(value) && isPositive(value.price) && isCounter(value.quantity) && value.quantity > 0
}

export async function getMarketDepth(symbol: string, signal?: AbortSignal): Promise<MarketDepth> {
  const data = await getJson(`/api/stocks/${encodeURIComponent(symbol)}/depth?levels=5`, signal)
  if (!isRecord(data) || data.symbol !== symbol || !Array.isArray(data.bids) || !Array.isArray(data.asks)
    || data.bids.length > 5 || data.asks.length > 5 || !data.bids.every(isLevel) || !data.asks.every(isLevel)
    || !data.bids.every((level, index, levels) => index === 0 || levels[index - 1].price > level.price)
    || !data.asks.every((level, index, levels) => index === 0 || levels[index - 1].price < level.price)) {
    throw new Error('The market depth response is invalid.')
  }
  return { symbol, bids: data.bids, asks: data.asks }
}

function isTrade(value: unknown, symbol: string): value is MarketTrade {
  return isRecord(value) && value.symbol === symbol && isCounter(value.tradeId) && value.tradeId > 0
    && isPositive(value.executionPrice) && isCounter(value.quantity) && value.quantity > 0 && isInstant(value.executedAt)
}

export async function getRecentTrades(symbol: string, signal?: AbortSignal): Promise<TradePage> {
  const data = await getJson(`/api/stocks/${encodeURIComponent(symbol)}/trades?page=0&size=10`, signal)
  if (!isRecord(data) || !Array.isArray(data.content) || data.content.length > 10
    || !data.content.every((trade): trade is MarketTrade => isTrade(trade, symbol))
    || !data.content.every((trade, index, trades) => index === 0 || trades[index - 1].tradeId > trade.tradeId)
    || data.page !== 0 || data.size !== 10 || !isCounter(data.totalElements) || !isCounter(data.totalPages)
    || data.totalElements < data.content.length || data.totalPages !== Math.ceil(data.totalElements / 10)) {
    throw new Error('The recent trades response is invalid.')
  }
  return { content: data.content, page: 0, size: 10, totalElements: data.totalElements, totalPages: data.totalPages }
}

function isCandle(value: unknown): value is Candle {
  return isRecord(value) && isInstant(value.startTime) && Date.parse(value.startTime) % 60_000 === 0
    && isPositive(value.open) && isPositive(value.high) && isPositive(value.low) && isPositive(value.close)
    && value.low <= Math.min(value.open, value.close) && value.high >= Math.max(value.open, value.close)
    && value.high >= value.low && isCounter(value.volume) && value.volume > 0
    && isCounter(value.tradeCount) && value.tradeCount > 0
}

export async function getCandles(symbol: string, signal?: AbortSignal): Promise<Candle[]> {
  const data = await getJson(`/api/stocks/${encodeURIComponent(symbol)}/candles?limit=100`, signal)
  if (!Array.isArray(data) || data.length > 100 || !data.every(isCandle)
    || !data.every((candle, index, candles) => index === 0 || Date.parse(candles[index - 1].startTime) < Date.parse(candle.startTime))) {
    throw new Error('The candle response is invalid.')
  }
  return data
}
