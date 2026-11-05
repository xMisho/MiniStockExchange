import { useEffect, useState } from 'react'
import { getExternalQuote, getMarketConnection } from '../api/externalMarket'
import { useSymbolResource } from '../hooks/useSymbolResource'
import type { ExternalQuote, ExternalResponse, Instrument, MarketConnection } from '../types/externalMarket'
import { formatChange } from '../utils/format'
import { ExternalMarketSummary } from '../components/ExternalMarketSummary'
import { ExternalPriceChart } from '../components/ExternalPriceChart'
import { PriceValue } from '../components/PriceValue'
import { UpdateStatus } from '../components/UpdateStatus'
import { useAuth } from '../auth/authState'
import { getWatchlist, saveWatch, removeWatch } from '../api/workspace'
import { SymbolSearch } from '../components/workspace/SymbolSearch'
import { MarketSession } from '../components/workspace/MarketSession'
import { PriceAlerts } from '../components/workspace/PriceAlerts'

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
  const auth = useAuth()
  const watch = useSymbolResource(auth.user ? `watchlist:${auth.user.id}` : null, getWatchlist, 60000)
  const [picked, setPicked] = useState<Instrument | null>(null)
  const [saving, setSaving] = useState(false)
  const [watchError, setWatchError] = useState<string | null>(null)
  const [connection, setConnection] = useState<MarketConnection | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    getMarketConnection(controller.signal).then(data => { if (!controller.signal.aborted) { setConnection(data); setError(null) } }).catch((failure: unknown) => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Couldn’t load the market connection.') })
    return () => controller.abort()
  }, [attempt])
  const symbol = picked?.symbol ?? (watch.resource.status === 'ready' ? watch.resource.data[0]?.symbol : undefined) ?? connection?.instruments[0]?.symbol ?? 'AAPL'
  const quote = useSymbolResource(connection?.configured ? `external:${symbol}` : null, getExternalQuote, 15000)
  const response = quote.resource.status === 'ready' ? quote.resource.data : null
  const saved = watch.resource.status === 'ready' ? watch.resource.data : []
  const instruments = [...new Map([...(connection?.instruments ?? []), ...saved].map(item => [item.symbol, item])).values()]
  const instrument = picked?.symbol === symbol ? picked : instruments.find(item => item.symbol === symbol) ?? connection?.instruments.find(item => item.symbol === symbol)
  const select = (item: Instrument) => { setPicked(item) }
  const toggleWatch = async () => {
    if (!instrument || saving || auth.busy) return
    setSaving(true); setWatchError(null); const release = auth.holdSession()
    try { if (saved.some(item => item.symbol === instrument.symbol)) await removeWatch(instrument.symbol); else await saveWatch(instrument.symbol); watch.retry() }
    catch (e) { setWatchError(e instanceof Error ? e.message : 'Watchlist update failed.'); watch.retry() }
    finally { setSaving(false); release() }
  }
  return <>
    <section className="dashboard-intro" id="market"><div className="workspace-kicker"><p className="eyebrow">REAL MARKET DATA · LOCAL WORKSPACE</p>{connection?.configured && <UpdateStatus state={quote.resource.status} refreshError={quote.refreshError || response?.stale === true} />}</div>
      <h1>Your market, at a glance.</h1><p className="dashboard-description">External share prices and historical charts from Alpaca’s IEX feed. Browse external quotes and charts. Local Exchange is a separate simulated market.</p>
      </section>
    {connection?.configured && <><MarketSession /><SymbolSearch onSelect={select} /></>}
    {error && <div className="page-message" role="alert"><p>{error}</p><button type="button" className="primary-button" onClick={() => setAttempt(value => value + 1)}>Retry market connection</button></div>}
    {!connection && !error && <p className="panel-message" role="status">Loading market connection…</p>}
    {connection && <>
      <div className="stock-panel"><div className="panel-heading"><h2>External watchlist</h2><span>Starter stocks + saved favorites · Alpaca · IEX · USD</span></div><div className="stock-list external-stock-grid" role="group" aria-label="Select an external stock">{instruments.map((item) => <QuoteCard key={item.symbol} instrument={item} selected={symbol === item.symbol} snapshot={response} configured={connection.configured} onSelect={() => select(item)} />)}</div></div>
      {auth.user && instrument && <div className="watch-actions"><button className="text-button" disabled={saving || auth.busy} onClick={() => { void toggleWatch() }}>{saving ? 'Saving…' : saved.some(item => item.symbol === symbol) ? `Remove ${symbol} from watchlist` : `Save ${symbol} to watchlist`}</button><span>Saved to your account · Up to 20 stocks</span></div>}
      {!auth.user && <p className="panel-footnote">Sign in to save your watchlist across visits.</p>}
      {(watchError || watch.errorMessage) && <p role="alert">{watchError ?? watch.errorMessage}</p>}
      {!connection.configured ? <section className="page-message provider-setup" id="market-charts"><p className="eyebrow">MARKET DATA CONNECTION REQUIRED</p><h2>Connect Alpaca to load real prices</h2><p>Configure <code>ALPACA_API_KEY</code> and <code>ALPACA_API_SECRET</code> in the server’s private <code>.env</code>, then restart Spring Boot. Keep these credentials out of frontend files.</p><p><a href="https://app.alpaca.markets/signup" target="_blank" rel="noreferrer">Create an Alpaca account ↗</a> · <a href="https://docs.alpaca.markets/us/docs/market-data-faq" target="_blank" rel="noreferrer">Feed and credentials guide ↗</a></p><p className="panel-footnote">IEX covers one exchange. Quote timestamps show the latest reported trade; historical charts intentionally exclude the newest 15 minutes. Public redistribution needs suitable provider permissions.</p></section>
        : instrument && <div className="market-workspace external-workspace" id="market-charts"><ExternalMarketSummary instrument={instrument} response={response} loading={quote.resource.status === 'loading'} error={quote.errorMessage} retry={quote.retry} /><ExternalPriceChart key={symbol} symbol={symbol} /></div>}
      {connection.configured && instrument && <PriceAlerts key={auth.user?.id ?? 'anonymous'} symbol={symbol} instruments={[...connection.instruments, ...saved, instrument]} />}
    </>}

  </>
}
