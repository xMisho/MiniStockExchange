import { useEffect, useState } from 'react'
import { getStocks } from '../api/stocks'
import { getMarketSnapshot } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import type { StockMetadata } from '../types/market'
import { formatChange, formatCount, formatPrice } from '../utils/format'
import { PriceValue } from '../components/PriceValue'
import { CandleChart } from '../components/CandleChart'
import { MarketDepth } from '../components/MarketDepth'
import { RecentTrades } from '../components/RecentTrades'
import { TradingWorkspace } from '../components/trading/TradingWorkspace'
import { useAuth } from '../auth/authState'

export function LocalExchange({ view, onShowOrders }: { view: 'local' | 'orders' | 'hidden'; onShowOrders: () => void }) {
  const [stocks, setStocks] = useState<StockMetadata[] | null>(null)
  const [symbol, setSymbol] = useState<string | null>(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const auth = useAuth()
  useEffect(() => {
    const controller = new AbortController()
    getStocks(controller.signal).then(items => {
      if (controller.signal.aborted) return
      setStocks(items); setError(false)
      setSymbol(current => items.some(item => item.symbol === current) ? current : items[0]?.symbol ?? null)
    }).catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => controller.abort()
  }, [attempt])
  const summary = useSymbolResource(view === 'local' ? symbol : null, getMarketSnapshot)
  const snapshot = summary.resource.status === 'ready' ? summary.resource.data : null
  const selected = stocks?.find(item => item.symbol === symbol)
  const chart = view === 'local' && symbol ? <CandleChart key={symbol} symbol={symbol} /> : null
  const book = view === 'local' && symbol ? <div className="lower-panels"><MarketDepth key={`depth:${symbol}`} symbol={symbol} /><RecentTrades key={`trades:${symbol}`} symbol={symbol} /></div> : null
  return <>
    <section hidden={view === 'hidden'} className="local-intro dashboard-intro">
      <p className="eyebrow">LOCAL EXCHANGE · SIMULATED MATCHING</p>
      <h1>{view === 'orders' ? 'Your local orders.' : 'Your trading workspace.'}</h1>
      <p className="dashboard-description">Orders match Verdex’s local book, with no real money or broker routing. External Alpaca prices do not determine these fills.</p>
    </section>
    <div hidden={view !== 'local'}>
      {error ? <div role="alert" className="page-message"><p>Couldn’t load local stocks.</p><button className="primary-button" onClick={() => setAttempt(value => value + 1)}>Retry local stocks</button></div>
        : !stocks ? <p role="status" className="panel-message">Loading local stocks…</p>
          : stocks.length === 0 ? <div className="page-message"><h2>No local stocks registered</h2><p>An administrator needs to register a stock before trading can begin.</p></div>
            : <section className="local-ticker" aria-label="Local market summary">
              <label>Local stock<select value={symbol ?? ''} onChange={event => setSymbol(event.target.value)}>{stocks.map(stock => <option key={stock.symbol} value={stock.symbol}>{stock.symbol} · {stock.companyName}</option>)}</select></label>
              <div className="local-last"><span className="metric-label">{symbol} · LOCAL LAST</span><strong><PriceValue value={snapshot?.lastTradedPrice ?? null} /></strong><span className={(snapshot?.priceChange ?? 0) < 0 ? 'negative' : 'positive'}>{formatChange(snapshot?.priceChange ?? null)} {snapshot?.priceChangePercent == null ? '' : `(${formatChange(snapshot.priceChangePercent)}%)`}</span></div>
              <div><span className="metric-label">BID / ASK</span><strong>{formatPrice(snapshot?.bestBidPrice ?? null)} / {formatPrice(snapshot?.bestAskPrice ?? null)}</strong></div>
              <div><span className="metric-label">VOLUME / TRADES</span><strong>{snapshot ? `${formatCount(snapshot.volume)} / ${formatCount(snapshot.tradeCount)}` : '—'}</strong></div>
              {(summary.refreshError || summary.resource.status === 'error') && <p role="status" className="source-warning">Local quotes could not refresh. <button className="text-button" onClick={summary.retry}>Retry local quotes</button></p>}
            </section>}
      {!auth.user && selected && <div className="guest-local-panels">{chart}{book}</div>}
    </div>
    <TradingWorkspace symbol={selected?.symbol ?? null} view={view} chart={chart} book={book} onShowOrders={onShowOrders} />
  </>
}
