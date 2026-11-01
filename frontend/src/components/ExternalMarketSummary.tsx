import type { ExternalQuote, ExternalResponse, Instrument } from '../types/externalMarket'
import { formatChange, formatCount, formatPrice, formatUtcDateTime } from '../utils/format'
import { PriceValue } from './PriceValue'
interface Props { instrument: Instrument; response: ExternalResponse<ExternalQuote> | null; loading: boolean; error: string | null; retry: () => void }
const shares = (value: number | null) => value === null ? '—' : new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(value)
export function ExternalMarketSummary({ instrument, response, loading, error, retry }: Props) {
  const quote = response?.data
  const tone = quote?.change == null || quote.change === 0 ? 'neutral' : quote.change > 0 ? 'positive' : 'negative'
  return <section className="summary-panel" aria-label={`${instrument.symbol} external market quote`} aria-busy={loading && !quote}>
    <div className="summary-heading"><div><p className="eyebrow">EXTERNAL MARKET · USD</p><h2>{instrument.symbol}</h2><p className="company-name">{instrument.companyName}</p></div><span className="market-label">Alpaca · IEX</span></div>
    {!quote ? <div className="summary-message" role={loading ? 'status' : 'alert'}><p>{loading ? 'Loading external market quote…' : error ?? 'External quote unavailable.'}</p>{!loading && <button type="button" className="primary-button" onClick={retry}>Retry quote</button>}</div> : <>
      <div className="price-block"><p className="metric-label">LATEST REPORTED TRADE</p><p className="last-price"><PriceValue key={instrument.symbol} value={quote.price} /></p>
        <p className={`price-change ${tone}`}>{formatChange(quote.change)} ({quote.changePercent === null ? '—' : `${formatChange(quote.changePercent)}%`})</p>
        <p className="reference-note">Change vs previous session close on IEX.</p>
        <p className="source-timestamp">As of {quote.priceAsOf ? formatUtcDateTime(quote.priceAsOf) : 'timestamp unavailable'}</p></div>
      <dl className="quote-grid"><div><dt>IEX BID</dt><dd>{formatPrice(quote.bid)}</dd></div><div><dt>IEX ASK</dt><dd>{formatPrice(quote.ask)}</dd></div><div><dt>PREV. CLOSE</dt><dd>{formatPrice(quote.previousClose)}</dd></div></dl>
      <dl className="statistics-grid"><div><dt>SESSION OPEN</dt><dd>{formatPrice(quote.sessionOpen)}</dd></div><div><dt>HIGH / LOW</dt><dd>{formatPrice(quote.sessionHigh)} / {formatPrice(quote.sessionLow)}</dd></div>
        <div><dt>IEX VOLUME</dt><dd>{shares(quote.sessionVolume)}</dd></div><div><dt>IEX TRADES</dt><dd>{quote.sessionTradeCount === null ? '—' : formatCount(quote.sessionTradeCount)}</dd></div><div><dt>IEX VWAP</dt><dd>{formatPrice(quote.sessionVwap)}</dd></div></dl>
      <p className="source-timestamp">Bid/ask as of {quote.quoteAsOf ? formatUtcDateTime(quote.quoteAsOf) : 'timestamp unavailable'}.</p>
      <p className="panel-footnote">Session {quote.sessionDate ?? 'unavailable'} · IEX is one exchange; these figures are not total US-market volume. Markets may be closed; use the timestamp above.</p>
    </>}
    {(response?.stale || error && quote) && <p role="status" className="source-warning">{response?.warning ?? error} Last successful provider fetch: {response ? formatUtcDateTime(response.fetchedAt) : 'unavailable'}.</p>}
  </section>
}
