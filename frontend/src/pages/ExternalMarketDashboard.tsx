import { useEffect, useState } from 'react'
import { getExternalQuote, getMarketConnection } from '../api/externalMarket'
import { useSymbolResource } from '../hooks/useSymbolResource'
import type { ExternalQuote, ExternalResponse, Instrument, MarketConnection } from '../types/externalMarket'
import { formatChange } from '../utils/format'
import { ExternalMarketSummary } from '../components/ExternalMarketSummary'
import { ExternalPriceChart } from '../components/ExternalPriceChart'
import { PriceValue } from '../components/PriceValue'
import { UpdateStatus } from '../components/UpdateStatus'

function QuoteCard({ instrument, selected, snapshot, onSelect, configured }: { instrument: Instrument; selected: boolean; snapshot: ExternalResponse<ExternalQuote> | null; configured: boolean; onSelect: () => void }) {
  const { resource } = useSymbolResource(!selected && configured ? `external:${instrument.symbol}` : null, getExternalQuote, 15000)
  const response = selected ? snapshot : resource.status === 'ready' ? resource.data : null
  const quote = response?.data
  const tone = quote?.change == null || quote.change === 0 ? 'neutral' : quote.change > 0 ? 'positive' : 'negative'
  return <button type="button" className={`stock-option ${selected ? 'selected' : ''}`} aria-label={`${instrument.symbol} ${instrument.companyName}`} aria-pressed={selected} onClick={onSelect}>
    <span className="stock-symbol">{instrument.symbol}</span><span className="stock-company">{instrument.companyName} · USD</span>
    <span className="watch-quote"><strong><PriceValue value={quote?.price ?? null} /></strong><span className={tone}>{quote?.changePercent == null ? configured ? 'Awaiting provider' : 'Not connected' : `${formatChange(quote.changePercent)}%`}</span></span>
    {response?.stale && <span className="watch-stale">Last cached quote</span>}
  </button>
}
export function ExternalMarketDashboard() {
  const [connection, setConnection] = useState<MarketConnection | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [symbol, setSymbol] = useState<string>('AAPL')
  useEffect(() => {
    const controller = new AbortController()
    getMarketConnection(controller.signal).then(data => { if (!controller.signal.aborted) { setConnection(data); setError(null) } }).catch((failure: unknown) => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Couldn’t load the market connection.') })
    return () => controller.abort()
  }, [attempt])
  const quote = useSymbolResource(connection?.configured ? `external:${symbol}` : null, getExternalQuote, 15000)
  const response = quote.resource.status === 'ready' ? quote.resource.data : null
  const instrument = connection?.instruments.find(item => item.symbol === symbol)
  return <>
    <section className="dashboard-intro" id="market"><div className="workspace-kicker"><p className="eyebrow">REAL MARKET DATA · LOCAL WORKSPACE</p>{connection?.configured && <UpdateStatus state={quote.resource.status} refreshError={quote.refreshError || response?.stale === true} />}</div>
      <h1>Your market, at a glance.</h1><p className="dashboard-description">External share prices and historical charts from Alpaca’s IEX feed. Browse external quotes and charts. Local Exchange is a separate simulated market.</p>
      </section>
    {error && <div className="page-message" role="alert"><p>{error}</p><button type="button" className="primary-button" onClick={() => setAttempt(value => value + 1)}>Retry market connection</button></div>}
    {!connection && !error && <p className="panel-message" role="status">Loading market connection…</p>}
    {connection && <>
      <div className="stock-panel"><div className="panel-heading"><h2>External watchlist</h2><span>Alpaca · IEX · USD</span></div><div className="stock-list" role="group" aria-label="Select an external stock">{connection.instruments.map(item => <QuoteCard key={item.symbol} instrument={item} selected={symbol === item.symbol} snapshot={response} configured={connection.configured} onSelect={() => setSymbol(item.symbol)} />)}</div></div>
      {!connection.configured ? <section className="page-message provider-setup" id="market-charts"><p className="eyebrow">MARKET DATA CONNECTION REQUIRED</p><h2>Connect Alpaca to load real prices</h2><p>Configure <code>ALPACA_API_KEY</code> and <code>ALPACA_API_SECRET</code> in the server’s private <code>.env</code>, then restart Spring Boot. Keep these credentials out of frontend files.</p><p><a href="https://app.alpaca.markets/signup" target="_blank" rel="noreferrer">Create an Alpaca account ↗</a> · <a href="https://docs.alpaca.markets/us/docs/market-data-faq" target="_blank" rel="noreferrer">Feed and credentials guide ↗</a></p><p className="panel-footnote">IEX covers one exchange. Quote timestamps show the latest reported trade; historical charts intentionally exclude the newest 15 minutes. Public redistribution needs suitable provider permissions.</p></section>
        : instrument && <div className="market-workspace external-workspace" id="market-charts"><ExternalMarketSummary instrument={instrument} response={response} loading={quote.resource.status === 'loading'} error={quote.errorMessage} retry={quote.retry} /><ExternalPriceChart key={symbol} symbol={symbol} /></div>}
    </>}

  </>
}
