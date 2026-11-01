export interface Instrument { symbol: string; companyName: string; currency: 'USD' }
export interface MarketConnection { provider: 'Alpaca'; feed: 'IEX'; configured: boolean; instruments: Instrument[] }
export interface ExternalQuote {
  symbol: string; price: number | null; priceAsOf: string | null; bid: number | null; ask: number | null; quoteAsOf: string | null
  previousClose: number | null; change: number | null; changePercent: number | null
  sessionOpen: number | null; sessionHigh: number | null; sessionLow: number | null; sessionVolume: number | null
  sessionTradeCount: number | null; sessionVwap: number | null; sessionDate: string | null
}
export type HistoryRange = '1D' | '1M' | '1Y'
export interface ExternalBar { startTime: string; open: number; high: number; low: number; close: number; volume: number; tradeCount: number | null }
export interface ExternalHistory { symbol: string; range: HistoryRange; interval: '5Min' | '1Day'; delayMinutes: number; adjustment: 'split'; candles: ExternalBar[] }
export interface ExternalResponse<T> { provider: 'Alpaca'; feed: 'IEX'; currency: 'USD'; coverage: string; fetchedAt: string; stale: boolean; warning: string | null; data: T }
