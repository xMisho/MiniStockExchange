import { RefreshStatus } from './RefreshStatus'
import type { MarketSnapshot, StockMetadata } from '../types/market'
import { formatChange, formatCount, formatPrice } from '../utils/format'

export type SnapshotState =
  | { symbol: string; status: 'loading' }
  | { symbol: string; status: 'error' }
  | { symbol: string; status: 'ready'; data: MarketSnapshot }

interface Props {
  stock: StockMetadata
  snapshot: SnapshotState | null
  refresh?: { updatedAt: number | null; refreshError: boolean; refreshing: boolean }
  onRetry: () => void
}

export function MarketSummary({ stock, snapshot, onRetry, refresh }: Props) {
  const state = snapshot?.symbol === stock.symbol ? snapshot : null
  const data = state?.status === 'ready' ? state.data : null
  const tone = data?.priceChange == null || data.priceChange === 0 ? 'neutral' : data.priceChange > 0 ? 'positive' : 'negative'

  return (
    <section className="summary-panel" aria-labelledby="summary-title" aria-busy={!state || state.status === 'loading'}>
      <div className="summary-heading">
        <div><p className="eyebrow">MARKET SUMMARY</p><h2 id="summary-title">{stock.symbol}</h2><p className="company-name">{stock.companyName}</p></div>
        <span className="market-label">Local exchange</span>
      </div>
      {state?.status === 'error' ? (
        <div className="summary-message" role="alert">
          <h3>Summary unavailable</h3><p>We couldn’t load {stock.symbol}. Try again or select another stock.</p>
          <button className="primary-button" type="button" onClick={onRetry}>Retry summary <span aria-hidden="true">↗</span></button>
        </div>
      ) : !data ? (
        <div className="summary-message" role="status"><p>Loading {stock.symbol} market summary…</p></div>
      ) : (
        <>
          <div className="price-block">
            <p className="metric-label">LAST TRADED PRICE</p>
            <p className="last-price">{formatPrice(data.lastTradedPrice)}</p>
            <p className={`price-change ${tone}`} aria-label="Price change from reference">
              <span>{formatChange(data.priceChange)}</span>
              <span>({data.priceChangePercent === null ? '—' : `${formatChange(data.priceChangePercent)}%`})</span>
            </p>
            <p className="reference-note">Change from the stock’s reference price, not necessarily the previous close.</p>
          </div>
          <dl className="quote-grid">
            <div><dt>BEST BID</dt><dd>{formatPrice(data.bestBidPrice)}</dd></div>
            <div><dt>BEST ASK</dt><dd>{formatPrice(data.bestAskPrice)}</dd></div>
            <div><dt>SPREAD</dt><dd>{formatPrice(data.spread)}</dd></div>
          </dl>
          <dl className="statistics-grid">
            <div><dt>VOLUME <span>(shares)</span></dt><dd>{formatCount(data.volume)}</dd></div>
            <div><dt>TRADES</dt><dd>{formatCount(data.tradeCount)}</dd></div>
            <div><dt>VWAP</dt><dd>{formatPrice(data.vwap)}</dd></div>
            <div><dt>OPEN ORDERS</dt><dd>{formatCount(data.openOrderCount)}</dd></div>
          </dl>
          {data.tradeCount === 0 && <p className="no-executions">No executions yet. Resting quotes may be available; traded prices appear after orders match.</p>}
          <p className="summary-note">Refreshes every 2 seconds while this tab is visible</p>
        </>
      )}
      {refresh && <RefreshStatus {...refresh} onRetry={onRetry} />}
    </section>
  )
}
