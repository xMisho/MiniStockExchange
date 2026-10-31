export interface StockMetadata {
  symbol: string
  companyName: string
  referencePrice: number
}

export interface MarketSnapshot {
  symbol: string
  lastTradedPrice: number | null
  priceChange: number | null
  priceChangePercent: number | null
  bestBidPrice: number | null
  bestAskPrice: number | null
  spread: number | null
  volume: number
  tradeCount: number
  openOrderCount: number
  vwap: number | null
}

export interface PriceLevel { price: number; quantity: number }
export interface MarketDepth { symbol: string; bids: PriceLevel[]; asks: PriceLevel[] }
export interface MarketTrade { tradeId: number; symbol: string; executionPrice: number; quantity: number; executedAt: string }
export interface TradePage { content: MarketTrade[]; page: number; size: number; totalElements: number; totalPages: number }
export interface Candle { startTime: string; open: number; high: number; low: number; close: number; volume: number; tradeCount: number }
