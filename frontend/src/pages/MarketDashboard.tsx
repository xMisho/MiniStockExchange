import { useEffect, useState } from 'react'
import { getStocks } from '../api/stocks'
import { getMarketSnapshot } from '../api/marketData'
import { StockList } from '../components/StockList'
import { MarketPanels } from '../components/MarketPanels'
import { MarketSummary } from '../components/MarketSummary'
import type { SnapshotState } from '../components/MarketSummary'
import type { StockMetadata } from '../types/market'

type StockState = { status: 'loading' } | { status: 'error' } | { status: 'ready'; stocks: StockMetadata[] }

export function MarketDashboard() {
  const [stockState, setStockState] = useState<StockState>({ status: 'loading' })
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState<SnapshotState | null>(null)
  const [listAttempt, setListAttempt] = useState(0)
  const [snapshotAttempt, setSnapshotAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    getStocks(controller.signal)
      .then((stocks) => {
        if (controller.signal.aborted) return
        setStockState({ status: 'ready', stocks })
        setSelectedSymbol((current) => stocks.some((stock) => stock.symbol === current) ? current : stocks[0]?.symbol ?? null)
      })
      .catch(() => {
        if (!controller.signal.aborted) setStockState({ status: 'error' })
      })
    return () => controller.abort()
  }, [listAttempt])

  useEffect(() => {
    if (selectedSymbol === null) return
    const controller = new AbortController()
    getMarketSnapshot(selectedSymbol, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setSnapshot({ symbol: selectedSymbol, status: 'ready', data })
      })
      .catch(() => {
        if (!controller.signal.aborted) setSnapshot({ symbol: selectedSymbol, status: 'error' })
      })
    return () => controller.abort()
  }, [selectedSymbol, snapshotAttempt])

  const stocks = stockState.status === 'ready' ? stockState.stocks : []
  const selected = stocks.find((stock) => stock.symbol === selectedSymbol)

  return (
    <>
      <section className="dashboard-intro" aria-labelledby="dashboard-title">
        <p className="eyebrow">V3 / MARKET WORKSPACE</p>
        <h1 id="dashboard-title">Your market, at a glance.</h1>
        <p>Select a stock to explore its current quotes and execution statistics.</p>
      </section>
      {stockState.status === 'loading' ? <section className="page-message" role="status">Loading registered stocks…</section>
        : stockState.status === 'error' ? (
          <section className="page-message" role="alert"><h2>Couldn’t load stocks</h2><p>Make sure Spring Boot is running, then try again.</p>
            <button className="primary-button" type="button" onClick={() => { setStockState({ status: 'loading' }); setListAttempt((value) => value + 1) }}>Retry stocks <span aria-hidden="true">↗</span></button>
          </section>
        ) : stocks.length === 0 ? (
          <section className="page-message"><p className="eyebrow">NO LISTINGS YET</p><h2>No stocks registered</h2><p>An administrator needs to register a stock before this workspace can display market data.</p></section>
        ) : (
          <div className="dashboard-grid">
            <StockList stocks={stocks} selectedSymbol={selectedSymbol} onSelect={(symbol) => {
              if (symbol === selectedSymbol) return
              setSnapshot({ symbol, status: 'loading' })
              setSelectedSymbol(symbol)
            }} />
            {selected && <div className="market-workspace"><MarketSummary stock={selected} snapshot={snapshot} onRetry={() => {
              setSnapshot({ symbol: selected.symbol, status: 'loading' })
              setSnapshotAttempt((value) => value + 1)
            }} /><MarketPanels key={selected.symbol} symbol={selected.symbol} /></div>}
          </div>
        )}
    </>
  )
}
